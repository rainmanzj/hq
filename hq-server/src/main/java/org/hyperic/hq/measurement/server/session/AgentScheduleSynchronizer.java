/*
 * NOTE: This copyright does *not* cover user programs that use HQ
 * program services by normal system calls through the application
 * program interfaces provided as part of the Hyperic Plug-in Development
 * Kit or the Hyperic Client Development Kit - this is merely considered
 * normal use of the program, and does *not* fall under the heading of
 * "derived work".
 * 
 * Copyright (C) [2004-2011], VMWare, Inc.
 * This file is part of HQ.
 * 
 * HQ is free software; you can redistribute it and/or modify
 * it under the terms version 2 of the GNU General Public License as
 * published by the Free Software Foundation. This program is distributed
 * in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307
 * USA.
 */

package org.hyperic.hq.measurement.server.session;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import javax.annotation.PostConstruct;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hyperic.hq.agent.server.session.AgentDataTransferJob;
import org.hyperic.hq.agent.server.session.AgentSynchronizer;
import org.hyperic.hq.appdef.Agent;
import org.hyperic.hq.appdef.shared.AgentManager;
import org.hyperic.hq.appdef.shared.AgentNotFoundException;
import org.hyperic.hq.appdef.shared.AppdefEntityID;
import org.hyperic.hq.authz.shared.ResourceManager;
import org.hyperic.hq.common.SystemException;
import org.hyperic.hq.context.Bootstrap;
import org.hyperic.hq.hibernate.SessionManager;
import org.hyperic.hq.hibernate.SessionManager.SessionRunner;
import org.hyperic.hq.measurement.shared.MeasurementProcessor;
import org.hyperic.hq.stats.ConcurrentStatsCollector;
import org.hyperic.hq.zevents.Zevent;
import org.hyperic.hq.zevents.ZeventEnqueuer;
import org.hyperic.hq.zevents.ZeventListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * This class is used to schedule and unschedule metrics for a given entity. The
 * schedule operation is synchronized to throttle rescheduling.
 */
@Component
public class AgentScheduleSynchronizer {

    private final Log log = LogFactory.getLog(AgentScheduleSynchronizer.class.getName());

    private ZeventEnqueuer zEventManager;

    private AgentManager agentManager;

    private final Map<Integer, Collection<AppdefEntityID>> scheduleAeids =
        new HashMap<Integer, Collection<AppdefEntityID>>();

    private final Map<Integer, Collection<AppdefEntityID>> unscheduleAeids =
        new HashMap<Integer, Collection<AppdefEntityID>>();
    
    private ConcurrentStatsCollector concurrentStatsCollector;
    private MeasurementProcessor measurementProcessor;
    private AgentSynchronizer agentSynchronizer;
    
    @Autowired
    public AgentScheduleSynchronizer(ZeventEnqueuer zEventManager, AgentManager agentManager,
                                     MeasurementProcessor measurementProcessor,
                                     AgentSynchronizer agentSynchronizer,
                                     ConcurrentStatsCollector concurrentStatsCollector) {
        this.zEventManager = zEventManager;
        this.agentManager = agentManager;
        this.measurementProcessor = measurementProcessor;
        this.concurrentStatsCollector = concurrentStatsCollector;
        this.agentSynchronizer = agentSynchronizer;
    }

    @PostConstruct
    void initialize() {
        ZeventListener<Zevent> l = getScheduleListener();
        zEventManager.addBufferedListener(AgentScheduleSyncZevent.class, l);
        zEventManager.addBufferedListener(AgentUnscheduleZevent.class, l);

        final ZeventListener<AgentUnscheduleNonEntityZevent> l2 = getUnscheduleUnEntityZeventListener();
        zEventManager.addBufferedListener(AgentUnscheduleNonEntityZevent.class, l2);
        concurrentStatsCollector.register(ConcurrentStatsCollector.SCHEDULE_QUEUE_SIZE);
        concurrentStatsCollector.register(ConcurrentStatsCollector.UNSCHEDULE_QUEUE_SIZE);
    }

    private ZeventListener<AgentUnscheduleNonEntityZevent> getUnscheduleUnEntityZeventListener() {
        return new ZeventListener<AgentUnscheduleNonEntityZevent>() {
            @Transactional
            public void processEvents(List<AgentUnscheduleNonEntityZevent> events) {
                final ResourceManager rMan = Bootstrap.getBean(ResourceManager.class);
                final boolean debug = log.isDebugEnabled();
                final Map<Integer, Collection<AppdefEntityID>> toUnschedule =
                    new HashMap<Integer, Collection<AppdefEntityID>>();
                for (final AgentUnscheduleNonEntityZevent zevent : events) {
                    final Collection<AppdefEntityID> aeids = zevent.getAppdefEntities();
                    Integer agentId = null;
                    try {
                        agentId = agentManager.getAgent(zevent.getAgentToken()).getId();
                        if (agentId == null) {
                            continue;
                        }
                    } catch (AgentNotFoundException e) {
                        log.debug(e,e);
                        continue;
                    }
                    for (final AppdefEntityID aeid : aeids) {
                        if (null == rMan.findResource(aeid)) {
                            Collection<AppdefEntityID> tmp = toUnschedule.get(agentId);
                            if (tmp == null) {
                                tmp = new ArrayList<AppdefEntityID>();
                                toUnschedule.put(agentId, tmp);
                            }
                            if (debug) log.debug("unscheduling non-entity=" + aeid);
                            tmp.add(aeid);
                        }
                    }
                    synchronized (unscheduleAeids) {
                        unscheduleAeids.putAll(toUnschedule);
                    }
                }
            }
            public String toString() {
                return "AgentUnScheduleNonEntityListener";
            }
        };
    }

    private ZeventListener<Zevent> getScheduleListener() {
        return new ZeventListener<Zevent>() {
            public void processEvents(List<Zevent> events) {
                final List<AppdefEntityID> toSchedule = new ArrayList<AppdefEntityID>(events.size());
                final Map<String, Collection<AppdefEntityID>> unscheduleMap =
                    new HashMap<String, Collection<AppdefEntityID>>(events.size());
                final boolean debug = log.isDebugEnabled();
                for (final Zevent z : events) {
                    if (z instanceof AgentScheduleSyncZevent) {
                        AgentScheduleSyncZevent event = (AgentScheduleSyncZevent) z;
                        toSchedule.addAll(event.getEntityIds());
                        if (debug) log.debug("Schduling eids=[" + event.getEntityIds() + "]");
                    } else if (z instanceof AgentUnscheduleZevent) {
                        AgentUnscheduleZevent event = (AgentUnscheduleZevent) z;
                        String token = event.getAgentToken();
                        if (token == null) {
                            continue;
                        }
                        Collection<AppdefEntityID> tmp;
                        if (null == (tmp = unscheduleMap.get(token))) {
                            tmp = new HashSet<AppdefEntityID>();
                            unscheduleMap.put(token, tmp);
                        }
                        tmp.addAll(event.getEntityIds());
                        if (debug) log.debug("Unschduling eids=[" + event.getEntityIds() + "]");
                    }
                }
                final Map<Integer, Collection<AppdefEntityID>> agentAppdefIds =
                    agentManager.getAgentMap(toSchedule);
                synchronized (scheduleAeids) {
                    for (final Map.Entry<Integer, Collection<AppdefEntityID>> entry : agentAppdefIds.entrySet()) {
                        final Integer agentId = entry.getKey();
                        final Collection<AppdefEntityID> eids = entry.getValue();
                        Collection<AppdefEntityID> tmp;
                        if (null == (tmp = scheduleAeids.get(agentId))) {
                            tmp = new HashSet<AppdefEntityID>(eids.size());
                            scheduleAeids.put(agentId, tmp);
                        }
                        tmp.addAll(eids);
                        addScheduleJob(true, agentId);
                    }
                }
                synchronized (unscheduleAeids) {
                    for (Map.Entry<String, Collection<AppdefEntityID>> entry : unscheduleMap.entrySet()) {
                        final String token = entry.getKey();
                        final Collection<AppdefEntityID> eids = entry.getValue();
                        Integer agentId;
                        try {
                            agentId = agentManager.getAgent(token).getId();
                        } catch (AgentNotFoundException e) {
                            log.warn("Could not get agentToken=" + token +
                                     " from db to unschedule: " + e);
                            continue;
                        }
                        Collection<AppdefEntityID> tmp;
                        if (null == (tmp = unscheduleAeids.get(agentId))) {
                            tmp = new HashSet<AppdefEntityID>(eids.size());
                            unscheduleAeids.put(agentId, tmp);
                        }
                        tmp.addAll(eids);
                        addScheduleJob(false, agentId);
                    }
                }
            }
            public String toString() {
                return "AgentScheduleSyncListener";
            }
        };
    }

    private void addScheduleJob(final boolean schedule, final Integer agentId) {
        if (agentId == null) {
            return;
        }
        final AgentDataTransferJob job = new AgentDataTransferJob() {
            public String getJobDescription() {
                if (schedule) {
                    return "Agent Schedule Job";
                } else {
                    return "Agent UnSchedule Job";
                }
            }
            public int getAgentId() {
                return agentId;
            }
            public void execute() {
                Collection<AppdefEntityID> aeids = null;
                final Map<Integer, Collection<AppdefEntityID>> aeidMap =
                    (schedule) ? scheduleAeids : unscheduleAeids;
                synchronized (aeidMap) {
                    aeids = aeidMap.remove(agentId);
                }
                if (aeids != null && !aeids.isEmpty()) {
                    runSchedule(schedule, agentId, aeids);
                }
            }
        };
        if (schedule) {
            concurrentStatsCollector.addStat(
                scheduleAeids.size(), ConcurrentStatsCollector.SCHEDULE_QUEUE_SIZE);
        } else {
            concurrentStatsCollector.addStat(
                unscheduleAeids.size(), ConcurrentStatsCollector.UNSCHEDULE_QUEUE_SIZE);
        }
        agentSynchronizer.addAgentJob(job);
    }

    private void runSchedule(final boolean schedule, final Integer agentId,
                             final Collection<AppdefEntityID> aeids) {
        try {
            SessionManager.runInSession(new SessionRunner() {
                public void run() throws Exception {
                    final Agent agent = agentManager.getAgent(agentId);
                    if (agent == null) {
                        return;
                    }
                    if (schedule) {
                        if (log.isDebugEnabled()) {
                            log.debug("scheduling " + aeids.size() + " resources to agentid=" +
                                       agent.getId());
                        }
                        measurementProcessor.scheduleEnabled(agent, aeids);
                    } else {
                        if (log.isDebugEnabled()) {
                            log.debug("unscheduling " + aeids.size() + " resources to agentid=" +
                                       agent.getId());
                        }
                        measurementProcessor.unschedule(agent.getAgentToken(), aeids);
                    }
                }
                public String getName() {
                    if (schedule) {
                        return "Schedule";
                    } else {
                        return "Unschedule";
                    }
                }
            });
        } catch (Exception e) {
            throw new SystemException(e);
        }
    }

}

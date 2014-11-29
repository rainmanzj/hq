/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.hyperic.plugin.vrealize.automation;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Properties;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 *
 * @author glaullon
 */
public class VRAUtils {

    private static final Log log = LogFactory.getLog(VRAUtils.class);
    private static final Properties props = new Properties();

    protected static Properties configFile() {
        if (props.isEmpty()) {
            File configFile = new File("/etc/vcac/security.properties");
            if (configFile.exists()) {
                FileInputStream in = null;
                try {
                    in = new FileInputStream(configFile);
                    props.load(in);
                } catch (FileNotFoundException ex) {
                    log.debug(ex, ex);
                } catch (IOException ex) {
                    log.debug(ex, ex);
                } finally {
                    if (in != null) {
                        try {
                            in.close();
                        } catch (IOException ex) {
                            log.debug(ex, ex);
                        }
                    }
                }
            }
        }
        return props;
    }
}
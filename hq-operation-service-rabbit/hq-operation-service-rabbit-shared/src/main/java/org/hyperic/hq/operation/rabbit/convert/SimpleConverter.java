package org.hyperic.hq.operation.rabbit.convert;

/**
 * @author Helena Edelson
 */
public class SimpleConverter implements Converter<String, byte[]> {

    public byte[] fromObject(String source) {
        return source.getBytes();
    }

    public String toObject(byte[] source, Class<?> type) {
        return new String(source);
    }
}
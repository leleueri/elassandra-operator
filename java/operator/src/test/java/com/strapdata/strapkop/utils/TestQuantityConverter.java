package com.strapdata.strapkop.utils;

import static io.kubernetes.client.custom.Quantity.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static com.strapdata.strapkop.utils.QuantityConverter.*;

public class TestQuantityConverter {

    @Test
    public void testBasicMemoryQuantity() {
        String value = "1024";
        assertEquals(Integer.parseInt(value), toBytes(fromString(value)));
    }

    @Test
    public void testByteQuantity() {
        String byte_decimalSI = "128974848";
        String exp_decimalSI = "129e6";
        String mb_decimalSI = "129M";
        String mb_decimalBI = "123Mi"; // 123*1024^2 = 128974848

        assertEquals(Long.parseLong(byte_decimalSI), toBytes(fromString(byte_decimalSI)));
        assertEquals(129000000, toBytes(fromString(exp_decimalSI)));
        assertEquals(129000000, toBytes(fromString(mb_decimalSI)));
        assertEquals(Long.parseLong(byte_decimalSI), toBytes(fromString(mb_decimalBI)));
    }

    @Test
    public void testKiloByteQuantity() {
        String byte_decimalSI = "128974848";
        String exp_decimalSI = "129e6";
        String mb_decimalSI = "129M";
        String mb_decimalBI = "123Mi"; // 123*1024^2 = 128974848

        assertEquals(125952, toKiloBytes(fromString(byte_decimalSI)));
        assertEquals(125976, toKiloBytes(fromString(exp_decimalSI)));
        assertEquals(125976, toKiloBytes(fromString(mb_decimalSI)));
        assertEquals(125952, toKiloBytes(fromString(mb_decimalBI)));
    }

    @Test
    public void testMegaByteQuantity() {
        String byte_decimalSI = "128974848";
        String exp_decimalSI = "129e6";
        String mb_decimalSI = "129M";
        String mb_decimalBI = "123Mi"; // 123*1024^2 = 128974848

        assertEquals(123, toMegaBytes(fromString(byte_decimalSI)));
        assertEquals(123, toMegaBytes(fromString(exp_decimalSI)));
        assertEquals(123, toMegaBytes(fromString(mb_decimalSI)));
        assertEquals(123, toMegaBytes(fromString(mb_decimalBI)));
    }

    @Test
    public void testCpuQuantity() {
        String decimalSI = "2.5";
        String exp_decimalSI = "25e-1";
        String mb_decimalSI = "2500m";

        assertEquals(2, toCpu(fromString(decimalSI)));
        assertEquals(2, toCpu(fromString(exp_decimalSI)));
        assertEquals(2, toCpu(fromString(mb_decimalSI)));
    }

    @Test
    public void testCpuQuantityLessThanOne() {
        String decimalSI = "0.5";
        String exp_decimalSI = "5e-1";
        String mb_decimalSI = "500m";

        assertEquals(1, toCpu(fromString(decimalSI)));
        assertEquals(1, toCpu(fromString(exp_decimalSI)));
        assertEquals(1, toCpu(fromString(mb_decimalSI)));
    }
}

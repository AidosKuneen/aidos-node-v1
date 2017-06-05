package com.aidos.ari;

import java.util.HashMap;
import java.util.Map;
import com.aidos.ari.model.Hash;

public class Snapshot {

    public static final Map<Hash, Long> initialState = new HashMap<>();

    static {
    	initialState.put(new Hash("QL9SNLCUN9CAVAGOFYFVSXRYLEDIJSPNPWWZYXXYAAV9UKWTZFQF9CNJRHCWX9DQLPXWSPLFTEDMUVMID"), 2375426500000000L);
    	initialState.put(new Hash("JTCKPFUIIRGQ9PDHTYSUJYJPSPAYHLCT9VKGCU9GISYKKNCGFTRIGWWWLYRJKQLNIWVJQSUNF9IWARNCX"), 124573500000000L);
    }
}
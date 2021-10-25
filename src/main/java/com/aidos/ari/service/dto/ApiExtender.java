package com.aidos.ari.service.dto;

public class ApiExtender extends ClassLoader {
	
	// Allows Node operator to dynamically extend the API using the Nodes (private) authentication mechanism.
	
	byte[] ba;	
	String name = "";
	public ApiExtender(byte[] _ba, String _name) {
		ba = _ba;	
		name = _name;
	}
    
	public String performAPICall() {
		String response = "";
		try {
			Class loadedC = defineClass(name,ba,0,ba.length);
	    	Object lobj = loadedC.newInstance();
			response = (String) lobj.getClass().getMethod("APICALL").invoke(lobj);
		} catch (Exception e) {
			String errResult = "ERR: "+e.getMessage()+"\n";
			for (StackTraceElement ste : e.getStackTrace())
				errResult += ste.toString()+"\n";
			return errResult;
		}
        return response;
    }

}
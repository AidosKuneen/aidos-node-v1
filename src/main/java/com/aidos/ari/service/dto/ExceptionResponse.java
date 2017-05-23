package com.aidos.ari.service.dto;

public class ExceptionResponse extends AbstractResponse {
	
	private String exception;

	public static AbstractResponse create(String exception) {
		ExceptionResponse res = new ExceptionResponse();
		res.exception = exception;
		return res;
	}

	public String getException() {
		return exception;
	}
}

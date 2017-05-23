package com.aidos.ari.service.dto;

public class AccessLimitedResponse extends AbstractResponse {

    private String error;

    public static AbstractResponse create(String error) {
        AccessLimitedResponse res = new AccessLimitedResponse();
        res.error = error;
        return res;
    }

    public String getError() {
        return error;
    }
}

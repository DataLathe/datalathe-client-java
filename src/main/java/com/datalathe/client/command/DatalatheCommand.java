package com.datalathe.client.command;

public interface DatalatheCommand {
    String getEndpoint();

    Object getRequest();

    DatalatheCommandResponse getResponseType();

}

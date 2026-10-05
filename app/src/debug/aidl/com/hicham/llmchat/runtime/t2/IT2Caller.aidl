package com.hicham.llmchat.runtime.t2;

interface IT2Caller {
    void reset();
    void startB4(String operationId);
    void startB5(String operationId);
    String recover(String operationId);
    String getProcessInstanceId();
}

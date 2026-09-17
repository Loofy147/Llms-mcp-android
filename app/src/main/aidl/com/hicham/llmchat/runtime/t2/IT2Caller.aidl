package com.hicham.llmchat.runtime.t2;

interface IT2Caller {
    void startOperation(String operationId, int faultMode);
    void recover(String operationId);
    String getState(String operationId);
    int getPid();
    String getProcessInstanceId();
}

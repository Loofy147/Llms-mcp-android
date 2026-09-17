package com.hicham.llmchat.runtime.t2;

interface IT2Provider {
    void execute(String operationId, int faultMode);
    void reconcile(String operationId);
    String getState(String operationId);
    int getEffectCount(String operationId);
    int getPid();
    String getProcessInstanceId();
}

package com.qalab.qalabai.service.git;

public interface GitService {

    void cloneRepository(String repositoryUrl, String targetPath);

    void pullLatest(String workspacePath);

    String getStatus(String workspacePath);

    void createBranch(String workspacePath, String branchName);
}

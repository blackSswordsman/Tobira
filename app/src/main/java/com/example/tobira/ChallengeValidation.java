package com.example.tobira;

/** A scoped vehicle-answer evaluator owned by the task dialog. */
public interface ChallengeValidation {
    void onPermissionResult(boolean granted);
    void close();
}

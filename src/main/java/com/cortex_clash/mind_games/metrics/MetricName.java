package com.cortex_clash.mind_games.metrics;

public enum MetricName {
    USER_REGISTRATION_SUCCESS_COUNT("auth.user.registration.successes"),
    USER_REGISTRATION_ERROR_COUNT("auth.user.registration.errors"),
    USER_AUTHENTICATION_SUCCESS_COUNT("auth.user.login.successes"),
    USER_AUTHENTICATION_ERROR_COUNT("auth.user.login.errors"),
    PASSWORD_POLICY_ERROR_COUNT("auth.user.password.policy.failures"),
    REFRESH_TOKEN_CLEANUP_SUCCESS_COUNT("auth.user.refresh.token.cleanup.successes"),
    REFRESH_TOKEN_SUCCESS_COUNT("auth.user.refresh.token.successes"),
    REFRESH_TOKEN_ERROR_COUNT("auth.user.refresh.token.errors"),
    USER_PASSWORD_RESET_SUCCESS_COUNT("auth.user.password.reset.successes"),
    USER_PASSWORD_RESET_ERROR_COUNT("auth.user.password.reset.errors"),
    USER_PASSWORD_RESET_REQUEST_SUCCESS_COUNT("auth.user.password.reset.request.successes"),
    USER_PASSWORD_RESET_REQUEST_ERROR_COUNT("auth.user.password.reset.request.errors"),
    PASSWORD_RESET_CLEANUP_SUCCESS_COUNT("auth.user.password.reset.cleanup.successes");

    private final String metricName;

    MetricName(String metricName) {
        this.metricName = metricName;
    }

    public String metricName() {
        return metricName;
    }
}

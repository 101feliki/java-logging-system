package com.felixkariuki.insurelog.model;

/** Business area an insurance event belongs to. */
public enum Category {
    POLICY,        // quotes, new policies, renewals, cancellations
    CLAIM,         // submission, assessment, approval, rejection, payout
    PAYMENT,       // premiums, refunds, M-Pesa / card / bank callbacks
    UNDERWRITING,  // risk checks and pricing decisions
    CUSTOMER,      // KYC, profile changes, complaints
    SECURITY,      // logins, permission changes, suspicious activity
    SYSTEM         // integrations, jobs, application errors
}

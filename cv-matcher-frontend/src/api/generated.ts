/**
 * Generated from http://localhost:8080/v3/api-docs with `npm run api:generate`.
 * Do not edit manually.
 */

export interface components {
  schemas: {
    ApiError: {
      status: number;
      code?: string;
      message: string;
      timestamp: string;
      path: string;
      correlationId?: string;
    };
    UserInfo: {
      id: string;
      fullName: string;
      email: string;
      role: "RECRUITER" | "ADMIN";
      status: "PENDING_VERIFICATION" | "ACTIVE" | "DISABLED";
      forcePasswordChange: boolean;
    };
    TokenResponse: {
      accessToken: string;
      tokenType: string;
      expiresIn: number;
      user: components["schemas"]["UserInfo"];
      forcePasswordChange: boolean;
    };
  };
}

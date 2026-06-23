package com.llm.gateway.llm_gateway.exception;

/**
 * Internal/system-level failure that is unrelated to the validity of a specific caller request or
 * to provider selection (e.g. an unreachable JDK algorithm, or a thread interruption during
 * auto-failover). Always indicates a server-side problem.
 */
public class LlmGatewayInternalException extends RuntimeException {

  public LlmGatewayInternalException(String message) {
    super(message);
  }

  public LlmGatewayInternalException(String message, Throwable cause) {
    super(message, cause);
  }
}

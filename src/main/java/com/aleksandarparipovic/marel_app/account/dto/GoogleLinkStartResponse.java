package com.aleksandarparipovic.marel_app.account.dto;

/**
 * Where to send the person to let Google confirm who they are.
 *
 * <p>The URL is built on the server because it carries the single-use state the
 * server issued, which is also what binds this round-trip to THIS profile. A
 * client that assembled the URL itself would be choosing its own state, and the
 * state is the whole protection.
 */
public record GoogleLinkStartResponse(String authorizationUrl) {
}

import { defineRailway, preserve, project, service } from "railway/iac";

// Railway IaC for the ttl-live-api broker (see Dockerfile.live-api).
//
// Managed here: healthcheck and variable intent. NOT managed here:
// - Dockerfile selection (the DSL has no dockerfilePath key): set it once in the
//   dashboard at Service Settings -> Build -> Dockerfile Path = `Dockerfile.live-api`.
// - The public domain: generated Railway domains are excluded from IaC, so expose the
//   service once via Service Settings -> Networking -> Generate Domain.
// - The API_KEYS value itself: `preserve()` keeps whatever is set in the dashboard, so
//   the secret never lands in git. Set it once in Service Settings -> Variables.
//
// Workflow: `railway login && railway link`, then `railway config plan` /
// `railway config apply`. Run `railway config pull` first if names below drift from
// what the dashboard shows.
export default defineRailway(() => {
  const liveApi = service("tiktok-signer", {
    // Liveness probe. /readyz (signer warmed up) is the stricter sibling; the
    // platform healthcheck uses /healthz so deploys don't flap on a cold start.
    healthcheck: "/healthz",
    // Cold start = guest-identity bootstrap + embedded signer warm-up.
    healthcheckTimeout: 120,
    env: {
      // Kept in the dashboard, never in git. Required: without keys the public
      // broker mints tickets for anyone (and logs the non-loopback warning).
      API_KEYS: preserve(),
    },
  });

  return project("tiktok-signer", {
    resources: [liveApi],
  });
});

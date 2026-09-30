# Shell lifecycle implementation

Approved scope: bounded initialization and cleanup, opt-in isolated cancellable jobs, explicit root refresh, an integration skill, Maven Local validation in Thor, then the GitHub Central publishing workflow once required checks pass.

Implementation order:
1. Reproduce startup failures; introduce owned initialization attempts, one deadline, ready-only publication, interruptible scheduling and cleanup tests.
2. Prototype isolated process-group execution with an independent cancellation control path. Keep legacy persistent-shell execution unchanged. A job owns processes remaining in its group; intentionally detached processes and arbitrary non-cooperative Java initializers are outside the termination guarantee. Failure to establish termination/drain quarantines the transport.
3. Add an execution handle with idempotent cancel requests and an awaitable typed outcome. Retain existing coroutine cancellation behavior. No automatic replay after possible execution.
4. Add explicit invalidation and bounded refresh, covering both caches, concurrent requests and graceful retirement of accepted work. Preserve provider-selection behavior in Thor.
5. Publish a unique Maven Local candidate, adopt it in a separate Thor topic checkout, test the real provider, update the canonical integration skill and install copies for supported agents.
6. Review additive ABI changes and release version, run build/unit/lint/API/device/consumer gates, create topic PRs. Publish through GitHub Actions only after gates pass; never equate skipped device coverage with a pass.

Thor PR #530 merged during implementation. The dedicated integration checkout was refreshed to dev at 8de89948; the primary Thor checkout remains unchanged.

Required evidence: exact SHA/version, OS/API/root manager, fixture/marker-based ordering, process and worker cleanup, cancellation races, next-job output isolation, fresh root acquisition and remaining limitations.

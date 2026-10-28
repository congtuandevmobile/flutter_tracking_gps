# ForegroundHandshakeTest

The service-ready acknowledgement: ready within the timeout, not ready without it, a refused start surfaces as its failure, and an old acknowledgement never satisfies a new wait.

Also: overlapping requests are all satisfied or all failed, and only the newest request may stop the service.

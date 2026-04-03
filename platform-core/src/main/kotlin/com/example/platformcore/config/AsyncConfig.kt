package com.example.platformcore.config

// Worker thread pool removed: transaction processing now uses a single-threaded
// scheduler that drains the in-memory TransactionDispatchQueue and issues one
// batched UPDATE per cycle, eliminating the need for a separate @Async executor.

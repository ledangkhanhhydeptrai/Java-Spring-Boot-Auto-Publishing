# SocialHub worker integration starter

## Run isolated load simulator

```powershell
py -3 .\mock_load_test.py --accounts 100 --jobs-per-account 10 --workers 8
```

The script is purely synthetic: it does NOT connect to PostgreSQL, RabbitMQ, Facebook, or SocialHub. Do NOT interpret its throughput as end-to-end capacity.

## Two-process deployment to implement in the actual Spring project

- API role: serves HTTP and accepts new jobs; must not run any job-executing scheduler or RabbitMQ consumer.
- Worker role: web-application-type=none; runs exactly the chosen Phase 2C dispatcher and RabbitMQ consumer, and no legacy polling scheduler.
- Enable/disable Phase 2C classes via ConditionalOnProperty or profiles in their actual source files. Starting the same Spring Boot application twice without this is unsafe.
- LIVE worker must only process authorized Page accounts; keep MOCK test identities isolated from the live database or use a dedicated test DB.
- For exact-ID queued publish, existing FanpageQueueProcessor.publishQueuedJob(UUID) is the execution entry; phase2c dispatch/consumer sources are required to connect it correctly.
- When scheduled_at is changed directly in Postgres, the queue scheduler may have previously queued a message at the old time. Reconcile via actual dispatcher, not by assuming it polls.

## Required code for safe integrated changes

1. All classes containing `@RabbitListener`, `RabbitTemplate`, or `publishQueuedJob(` calls.
2. Phase 2C dispatcher/queue declaration, scheduling mechanism, and configuration `app.automation.live.*`.
3. Spring Boot application's `@SpringBootApplication` entrypoint and its scheduling/RabbitMQ config.

We can then produce two real launch configurations and a DB-backed 100-account MOCK test, while preserving the independent LIVE lane.

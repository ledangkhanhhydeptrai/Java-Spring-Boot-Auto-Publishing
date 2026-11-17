SocialHub Facebook Mock E2E — ONE-SHOT
======================================
1. Copy FacebookMockWorkerE2ETest.java into:
   src/test/java/com/socialhub/publishingjob/engine/
2. Ensure the isolated seed SQL has already created job
   e2000000-0000-4000-8000-000000000005 in socialhub_test.
3. The localhost Python Mock Server must be running, with GET /requests count=0.
4. Open a PowerShell terminal in the Spring Boot project root and set these
   variables IN THAT TERMINAL (IntelliJ Run Configuration variables are not
   automatically present in terminals):

   $env:TEST_DB_URL='jdbc:postgresql://localhost:5433/socialhub_test'
   $env:TEST_DB_USERNAME='<test-database-username>'
   $env:TEST_DB_PASSWORD='<test-database-password>'
   $env:META_PAGE_TOKEN_999000111222='mock_test_token'
   $env:SOCIALHUB_RUN_MOCK_E2E='YES'

5. Run only the E2E test (not all tests in one Maven run):
   mvn "-Dtest=FacebookMockWorkerE2ETest" test

This test intentionally commits one publishing attempt to socialhub_test.
Running it a second time without resetting the isolated fixture will fail
its preconditions. Do not change the URL, fake Page ID, or MOCK token.
No actual Facebook or Threads requests are made by this test harness.
The scheduler remains disabled: worker.processBatch() is invoked directly.

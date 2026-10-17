@echo off
rem AI tooling CLI: ai generate TC-103 ^| gate TC-103 ^| approve TC-103 Your Name ^| triage ^| status ^| app
rem Compiles first (quietly), then runs the CLI with the test classpath.
call "%~dp0mvnw.cmd" -q -B -f "%~dp0pom.xml" test-compile exec:exec@ai -Dcli="%*"

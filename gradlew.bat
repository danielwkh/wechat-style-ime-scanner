@echo off
set DIR=%~dp0
set CLASSPATH=%DIR%\gradle\wrapper\gradle-wrapper.jar
if not exist "%CLASSPATH%" (
  echo Gradle wrapper JAR is missing: %CLASSPATH% 1>&2
  exit /b 1
)
java %JAVA_OPTS% %GRADLE_OPTS% -Dorg.gradle.appname=gradlew -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*

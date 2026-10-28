@echo off
setlocal enabledelayedexpansion

set ANDROID_HOME=C:\Users\Colorful\AppData\Local\Android\Sdk
set JAVA_HOME=C:\Program Files (x86)\Android\openjdk\jdk-17.0.14
set PATH=!JAVA_HOME!\bin;!PATH!

"%JAVA_HOME%\bin\java" -Xmx4g -Dfile.encoding=UTF-8 ^
    -Dorg.gradle.appname=gradlew ^
    -classpath "%~dp0gradle\wrapper\gradle-wrapper.jar" ^
    org.gradle.wrapper.GradleWrapperMain %*

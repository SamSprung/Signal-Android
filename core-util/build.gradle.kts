plugins {
  id("signal-library")
  id("com.squareup.wire")
}

android {
  namespace = "org.signal.core.util"

  defaultConfig {
    buildConfigField("boolean", "VERBOSE_LOGGING", "false")
  }
}

dependencies {
  api(project(":core-util-jvm"))

  implementation(libs.androidx.sqlite)

  testImplementation(testLibs.junit.junit)
  testImplementation(testLibs.mockito.core)
  testImplementation(testLibs.robolectric.robolectric)
}

wire {
  kotlin {
    javaInterop = true
  }

  sourcePath {
    srcDir("src/main/protowire")
  }
}

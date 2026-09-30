plugins { id("com.android.application") version "8.10.1" apply false }
allprojects {
    layout.buildDirectory.set(file("${System.getProperty("user.home")}/.cache/antigravity-mobile-runtime/build/${project.name}"))
}

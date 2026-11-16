// Versions are pinned as a known-good set. Android Gradle Plugin, Kotlin and
// the Compose compiler have to agree with each other, and a mismatch produces
// errors that point at your own code rather than at the versions.
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}

// Build output lives OUTSIDE OneDrive.
//
// This project sits in a synced folder, and OneDrive holds files open while it
// uploads them. Gradle rewrites thousands of intermediates on every build, so
// the two fight: builds fail with "Unable to delete directory ... Failed to
// delete some children", which reads like a Gradle bug and is not one.
//
// Moving the output also stops OneDrive uploading a few hundred megabytes of
// disposable intermediates every time anything is compiled. Only the source
// stays synced, which is the only part worth syncing.
allprojects {
    if (System.getProperty("os.name").lowercase().contains("windows")) {
        val winBuild = file("C:/Users/user/AppData/Local/PumpPayBuild/${project.name}")
        if (winBuild.parentFile?.exists() == true) {
            layout.buildDirectory.set(winBuild)
        }
    }
}

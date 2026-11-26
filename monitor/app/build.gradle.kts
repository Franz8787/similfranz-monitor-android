plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
// ANDROID (categorie dei layout 22/11): -PsfPublic=true = build pubblica («Personalizzati» vuota); senza la cartella
// src/custom (repo pubblica appena scaricata) si usa src/public anche senza flag. Vedi docs/layout-personalizzati.md.
val sfPublic = (findProperty("sfPublic") as? String)?.toBoolean() == true || !file("src/custom/java").isDirectory
android {
    namespace = "it.simulafranz.monitor"
    compileSdk = 34
    defaultConfig {
        applicationId = "it.simulafranz.monitor"
        minSdk = 26          // Android 8.0; i tablet del progetto hanno Android 9
        targetSdk = 28       // Android 9: l'ultimo del Tab Active 2
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // niente Play Store: si installa l'APK a mano, il target resta Android 9 (Tab Active2)
    // ANDROID (audit 26/11, lint): WrongConstant e ViewConstructor sono falsi positivi dello strato uk: textAlignment è la
    // proprietà UIKit di UILabel (NSTextAlignment*, il setter non arriva a View) e le viste si creano solo da codice, mai da XML
    lint { disable += setOf("ExpiredTargetSdkVersion", "WrongConstant", "ViewConstructor") }
    // ANDROID (categorie dei layout 22/11): registro dei layout personalizzati (SFCustomLayouts) in src/custom;
    // la build pubblica (-PsfPublic=true) usa src/public (registro vuoto) e non compila le viste qui sotto.
    sourceSets { getByName("main") {
        java.srcDir(if (sfPublic) "src/public/java" else "src/custom/java")
        // ANDROID (miniature dei layout 23/11): miniature dei layout personalizzati (web-console/thumbs), se ci sono
        if (!sfPublic) assets.srcDir("src/custom/assets")
    } }
}
// File delle viste dei layout personalizzati (src/main/java/it/simulafranz/monitor): esclusi dalla build pubblica.
// Ogni layout personalizzato nuovo che sta in src/main va aggiunto qui (oppure messo direttamente in src/custom/java).
val sfCustomLayoutFiles = emptyList<String>()
if (sfPublic) tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    exclude { e -> !e.isDirectory && e.file.parentFile.name == "monitor" && e.name in sfCustomLayoutFiles }
}
dependencies {
    implementation(project(":uk"))
    implementation(project(":core-state"))
    implementation(project(":core-engine"))
    implementation(project(":core-api"))
}

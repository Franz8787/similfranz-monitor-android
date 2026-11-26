plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
// ANDROID (miniature dei layout 23/11): -PsfPublic=true = build pubblica: senza le miniature dei layout personalizzati
// (src/custom/assets/layout_thumbs); restano quelle dei layout originali (src/main/assets/layout_thumbs)
val sfPublic = (findProperty("sfPublic") as? String)?.toBoolean() == true || !file("src/custom/assets").isDirectory
android {
    sourceSets { getByName("main") { if (!sfPublic) assets.srcDir("src/custom/assets") } }
    namespace = "it.simulafranz.controller"
    compileSdk = 34
    defaultConfig {
        applicationId = "it.simulafranz.controller"
        minSdk = 26          // Android 8.0; i tablet del progetto hanno Android 9
        targetSdk = 28       // Android 9: l'ultimo del Tab Active 2 (e permette di collegarsi alla rete del monitor da app)
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
}
dependencies {
    implementation(project(":uk"))
    implementation(project(":core-state"))
}

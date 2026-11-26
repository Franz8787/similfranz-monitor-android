pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "SimilFranz_Controller_Android"
// Controller: usa gli stessi moduli del monitor (UIKit su Android e JSON/log/impostazioni), senza copiarli.
include(":uk", ":core-state", ":app")
project(":uk").projectDir = file("../monitor/uk")
project(":core-state").projectDir = file("../monitor/core-state")

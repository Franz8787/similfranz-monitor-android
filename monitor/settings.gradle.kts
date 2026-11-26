pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "SimilFranz_Monitor_Android"
// Il cuore (nessuna dipendenza Android, provabile sul Mac) è diviso in moduli che si compilano uno sull'altro.
include(":core-state", ":core-engine", ":core-api", ":core-tests", ":uk", ":app")

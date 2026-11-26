plugins { id("org.jetbrains.kotlin.jvm") }
kotlin { jvmToolchain(17) }
// ANDROID (categorie dei layout 22/11): i layout PERSONALIZZATI (catalogo: id, nome, tipo) stanno in src/custom;
// la build pubblica (-PsfPublic=true) usa src/public, dove l'elenco è vuoto. Vedi docs/layout-personalizzati.md.
// Senza la cartella src/custom (repo pubblica appena scaricata) si usa src/public anche senza flag.
val sfPublic = (findProperty("sfPublic") as? String)?.toBoolean() == true || !file("src/custom/kotlin").isDirectory
sourceSets { main { kotlin.srcDir(if (sfPublic) "src/public/kotlin" else "src/custom/kotlin") } }
dependencies {
}

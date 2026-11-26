# Ambiente per compilare e installare similFranz Monitor per Android: `source scripts/env.sh`
# Cerca Java 17 e l'SDK Android dove li mette Android Studio; si possono forzare con JAVA_HOME e ANDROID_HOME.
if [ -z "$JAVA_HOME" ]; then
  for j in "/Applications/Android Studio.app/Contents/jbr/Contents/Home" "$HOME/android-studio/jbr" "/opt/android-studio/jbr"; do
    [ -x "$j/bin/java" ] && { export JAVA_HOME="$j"; break; }
  done
fi
if [ -z "$ANDROID_HOME" ]; then
  for s in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do [ -d "$s" ] && { export ANDROID_HOME="$s"; break; }; done
fi
[ -n "$ANDROID_HOME" ] && export PATH="$ANDROID_HOME/platform-tools:$PATH"
[ -n "$JAVA_HOME" ] && export PATH="$JAVA_HOME/bin:$PATH"
command -v adb >/dev/null || echo "Manca adb: installa Android Studio (o i platform-tools) e riprova. Vedi docs/02-installazione.md" >&2

# Guide de résolution des problèmes de build

## Problème : Erreur de mémoire Java

L'erreur indique que :
1. Java 8 est utilisé au lieu de Java 17
2. La mémoire allouée (2GB) est trop élevée
3. Gradle 9.0-milestone-1 est une version instable

## Solutions

### Solution 1 : Utiliser Android Studio (Recommandé)

Android Studio gère automatiquement la version Java et Gradle correcte.

1. Ouvrez le projet dans Android Studio
2. Android Studio détectera et configurera automatiquement Java 17
3. Le build devrait fonctionner directement

### Solution 2 : Configurer Java 17 manuellement

#### Windows

1. Téléchargez et installez Java 17 (JDK) :
   - Oracle JDK : https://www.oracle.com/java/technologies/javase/jdk17-archive-downloads.html
   - OpenJDK : https://adoptium.net/temurin/releases/?version=17

2. Définissez la variable d'environnement JAVA_HOME :
   ```powershell
   # Dans PowerShell (en tant qu'administrateur)
   [System.Environment]::SetEnvironmentVariable('JAVA_HOME', 'C:\Program Files\Java\jdk-17', 'Machine')
   ```

3. Ajoutez Java 17 au PATH :
   ```powershell
   $env:Path = "C:\Program Files\Java\jdk-17\bin;$env:Path"
   ```

4. Vérifiez la version :
   ```powershell
   java -version
   # Devrait afficher : openjdk version "17.x.x"
   ```

#### Linux/Mac

1. Installez Java 17 :
   ```bash
   # Ubuntu/Debian
   sudo apt install openjdk-17-jdk
   
   # macOS (avec Homebrew)
   brew install openjdk@17
   ```

2. Définissez JAVA_HOME :
   ```bash
   export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
   export PATH=$JAVA_HOME/bin:$PATH
   ```

### Solution 3 : Utiliser une version stable de Gradle

Le fichier `gradle/wrapper/gradle-wrapper.properties` a été mis à jour pour utiliser Gradle 8.5 (version stable).

Si vous avez toujours des problèmes, vous pouvez forcer l'utilisation de Java 17 dans `gradle.properties` :

```properties
org.gradle.java.home=C:/Program Files/Java/jdk-17
```

### Solution 4 : Réduire la mémoire allouée

Le fichier `gradle.properties` a été mis à jour pour utiliser 1GB au lieu de 2GB.

Si vous avez toujours des problèmes de mémoire, réduisez encore :
```properties
org.gradle.jvmargs=-Xmx512m -Dfile.encoding=UTF-8
```

## Vérification

Après avoir configuré Java 17, vérifiez :

```powershell
# Vérifier la version Java
java -version

# Vérifier Gradle
./gradlew --version

# Nettoyer et rebuild
./gradlew clean
./gradlew assembleDebug
```

## Notes importantes

- **Java 17 est requis** pour ce projet (spécifié dans `app/build.gradle.kts`)
- Android Studio est la méthode recommandée car il gère tout automatiquement
- Si vous utilisez la ligne de commande, assurez-vous que JAVA_HOME pointe vers Java 17



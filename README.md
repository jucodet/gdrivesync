# GDriveSync - Application Android de Synchronisation Google Drive

Application Android qui synchronise un répertoire Google Drive paramétrable sur le stockage local de votre smartphone.

## Fonctionnalités

- ✅ Authentification avec Google Drive
- ✅ Sélection d'un dossier Google Drive à synchroniser
- ✅ Synchronisation bidirectionnelle (téléchargement et upload)
- ✅ Synchronisation manuelle et automatique
- ✅ **Synchronisation automatique dès qu'un fichier est modifié sur Google Drive**
- ✅ Gestion des fichiers et dossiers
- ✅ Interface utilisateur moderne avec Jetpack Compose
- ✅ Stockage local sécurisé avec Room Database

## Prérequis

- **Java 17 (JDK)** - Requis pour la compilation (Java 8 ne fonctionnera pas)
- Android Studio Hedgehog (2023.1.1) ou plus récent (recommandé)
- Android SDK 26 (Android 8.0) minimum
- Compte Google avec accès à Google Drive
- Configuration OAuth 2.0 pour Google Drive API

### Installation de Java 17

Si vous utilisez la ligne de commande pour compiler :

**Windows :**
1. Téléchargez Java 17 depuis [Adoptium](https://adoptium.net/temurin/releases/?version=17) ou [Oracle](https://www.oracle.com/java/technologies/javase/jdk17-archive-downloads.html)
2. Installez et définissez `JAVA_HOME` vers le répertoire d'installation (ex: `C:\Program Files\Java\jdk-17`)
3. Ajoutez `%JAVA_HOME%\bin` au PATH

**Linux/Mac :**
```bash
# Ubuntu/Debian
sudo apt install openjdk-17-jdk

# macOS (Homebrew)
brew install openjdk@17
```

**Note :** Si vous utilisez Android Studio, Java 17 est géré automatiquement et aucune configuration manuelle n'est nécessaire.

## Configuration

### 1. Configuration Google Drive API

1. Allez sur [Google Cloud Console](https://console.cloud.google.com/)
2. Créez un nouveau projet ou sélectionnez un projet existant
3. Activez l'API Google Drive
4. Créez des identifiants OAuth 2.0 :
   - Type : Application Android
   - Nom du package : `com.gdrivesync.app`
   - SHA-1 : Obtenez-le avec la commande :
     ```bash
     keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android
     ```
5. Téléchargez le fichier `google-services.json` (si nécessaire)

### 2. Configuration du projet

1. Ouvrez le projet dans Android Studio
2. Le projet devrait se synchroniser automatiquement avec Gradle
3. Si vous avez un fichier `google-services.json`, placez-le dans `app/`

### 3. Permissions

L'application demande automatiquement les permissions nécessaires :
- Internet
- Accès au stockage (pour Android 12 et inférieur)
- Accès aux médias (pour Android 13+)

## Utilisation

1. **Lancement de l'application**
   - Ouvrez l'application GDriveSync
   
2. **Authentification**
   - Allez dans les Paramètres
   - Cliquez sur "Se connecter à Google"
   - Sélectionnez votre compte Google
   - Autorisez l'accès à Google Drive

3. **Configuration de la synchronisation**
   - Dans les Paramètres, cliquez sur "Sélectionner un dossier Google Drive"
   - Choisissez le dossier à synchroniser
   - Le dossier local sera créé automatiquement dans le stockage de l'application

4. **Synchronisation**
   - **Synchronisation automatique par détection de changements** : L'application surveille automatiquement les modifications dans le dossier Google Drive configuré. Dès qu'un fichier est modifié, ajouté ou supprimé sur Google Drive, la synchronisation se déclenche automatiquement (vérification toutes les 5 minutes)
   - Synchronisation manuelle : Cliquez sur "Synchroniser maintenant" dans l'écran d'accueil
   - Synchronisation périodique : Activez-la dans les Paramètres et configurez l'intervalle (optionnel, en complément de la détection de changements)

## Structure du projet

```
app/
├── src/main/
│   ├── java/com/gdrivesync/app/
│   │   ├── data/
│   │   │   ├── database/          # Room Database pour les métadonnées
│   │   │   ├── google/            # Service Google Drive API
│   │   │   ├── preferences/       # Gestion des préférences
│   │   │   └── sync/              # Service de synchronisation
│   │   ├── ui/
│   │   │   ├── screens/           # Écrans Compose
│   │   │   ├── theme/             # Thème Material Design
│   │   │   ├── viewmodel/         # ViewModels
│   │   │   └── navigation/        # Navigation
│   │   ├── worker/                # Workers pour la synchronisation en arrière-plan
│   │   └── util/                  # Utilitaires
│   └── res/                       # Ressources Android
```

## Technologies utilisées

- **Kotlin** - Langage de programmation
- **Jetpack Compose** - Interface utilisateur moderne
- **Room Database** - Base de données locale
- **Google Drive API** - Intégration avec Google Drive
- **WorkManager** - Synchronisation en arrière-plan
- **DataStore** - Stockage des préférences
- **Coroutines** - Programmation asynchrone

## Développement

### Résolution des problèmes de build

Si vous rencontrez des erreurs de build (mémoire Java, version Java incorrecte), consultez le fichier `BUILD_FIX.md` pour les solutions détaillées.

### Compilation

```bash
# Vérifier la version Java (doit être 17)
java -version

# Nettoyer le projet
./gradlew clean

# Compiler
./gradlew assembleDebug
```

### Installation

```bash
./gradlew installDebug
```

### Tests

```bash
./gradlew test
```

## Notes importantes

- La première synchronisation peut prendre du temps selon le nombre de fichiers
- La synchronisation nécessite une connexion Internet
- Les fichiers sont stockés dans le répertoire privé de l'application
- Pour Android 13+, les permissions de stockage sont gérées automatiquement
- **Surveillance des changements** : L'application vérifie automatiquement les modifications sur Google Drive toutes les 5 minutes. Dès qu'un changement est détecté, la synchronisation se déclenche immédiatement
- La surveillance des changements fonctionne même lorsque l'application est en arrière-plan grâce à WorkManager

## Licence

Ce projet est fourni à titre éducatif et de démonstration.

## Support

Pour toute question ou problème, veuillez créer une issue sur le dépôt du projet.


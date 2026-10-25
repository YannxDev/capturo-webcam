# Capturo Webcam

[![Licence : GPL-3.0-or-later](https://img.shields.io/badge/licence-GPL--3.0--or--later-blue.svg)](LICENSE)

Application Android moderne qui transforme le téléphone en caméra réseau
pour **Capturo**. Elle est écrite en **Kotlin** avec **Jetpack Compose** et
**Material 3**.

## Principe

L'application capture la caméra avant ou arrière en JPEG et la diffuse en
**MJPEG** sur le réseau, avec le son du téléphone en **PCM 48 kHz mono** :

```
http://ADRESSE_DU_TELEPHONE:8080/video
http://ADRESSE_DU_TELEPHONE:8080/audio
```

La page `http://ADRESSE_DU_TELEPHONE:8080/` affiche les adresses du flux.
Capturo se connecte via **Caméra → Source → Caméra réseau (URL)**.

Aucun module noyau, aucun pilote, aucun root : tout passe par le réseau.
Le service de premier plan continue de diffuser quand l'écran est verrouillé
ou quand l'application passe en arrière-plan.

## Fonctionnalités

- **Aperçu en direct** dans l'application, avec arrêt/démarrage immédiat.
- **Résolutions** : 480p, 720p, 1080p, 1440p et **4K** (selon la caméra).
- **Qualité JPEG** : Fluide (70), Équilibrée (80), Maximale (90).
- **Profil rapide** 640 × 480 / qualité 70 si l'image saccade.
- **Caméra avant / arrière** : les boutons correspondent au capteur
  physique, y compris sur les appareils dont les constantes Camera2 sont
  inversées.
- **Micro du téléphone** activable/désactivable (PCM 48 kHz mono).
- **Images par seconde** : 15, 24, 30 ou 60 i/s, avec plafonnement
  automatique en 1080p/4K pour ménager le téléphone.
- **Torche** : interrupteur quand la caméra choisie a un flash. Elle
  s'éteint automatiquement à l'arrêt de la diffusion.
- **Jeton d'accès** : protège `/video` et `/audio` (HTTP 401 sans le bon
  jeton). Vide = accès libre sur le réseau local. Un bouton **Générer**
  crée un jeton de 8 caractères.
- **QR de l'adresse** : bouton dans **Connexion** pour scanner l'URL
  complète, jeton compris.
- **Maintien éveillé** : l'écran reste allumé pendant la diffusion et une
  veille partielle garde le service actif écran éteint.
- **Portrait et paysage** : l'interface s'adapte à la rotation.
- **Thème** : Automatique (suit Android), Clair ou Sombre.
- **Couleur d'accent** : Bleu, Violet, Rose, Rouge, Orange, Jaune, Vert,
  Turquoise ou Gris. La même couleur vive est conservée en mode sombre.
- Réglages mémorisés, reconnexion automatique de la caméra après une erreur
  du HAL, et notification de service.

## Installation

L'APK release **2.1** est installé sur le téléphone. Pour le réinstaller :

```bash
adb install -r dist/Capturo-Webcam-2.1.apk
```

Ou, après compilation :

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Les deux variantes utilisent la même clé, donc la mise à jour conserve les
réglages et les autorisations.

## Utilisation avec Capturo

1. Sur le téléphone, ouvrir **Capturo Webcam**.
2. Appuyer sur **Démarrer la diffusion**.
3. Relever l'adresse affichée, par exemple :

   ```
   http://192.168.1.65:8080/video   (wlan0)
   ```

   Avec un jeton, l'adresse contient `?token=…` et le QR de **Connexion**
   permet de la récupérer sur un autre appareil.

4. Sur le PC, dans Capturo :
   **Caméra → Source → Caméra réseau (URL)**, puis **Détecter**.
5. Activer **Incruster la caméra** et, si besoin, **Micro du téléphone**.

### Wi-Fi

Le téléphone et le PC doivent être sur le **même réseau Wi-Fi**. Pour la 4K,
préférer l'USB ou un Wi-Fi 5 GHz.

### USB (partage de connexion)

1. Brancher le téléphone en USB.
2. Activer **Partage de connexion USB** dans les réglages Android.
3. L'application affiche une adresse supplémentaire pour l'interface USB
   (souvent `192.168.42.x`). L'utiliser dans Capturo : plus stable et plus
   rapide que le Wi-Fi.

### USB via adb

Capturo détecte automatiquement un téléphone branché en USB lorsque `adb`
est installé et que le débogage USB est autorisé. Il crée lui-même le
transfert et utilise `http://127.0.0.1:8080/video`. Aucune commande n'est à
lancer à la main.

Si besoin, le transfert peut aussi être créé manuellement :

```bash
adb forward tcp:8080 tcp:8080
```

### Découverte automatique

L'application répond aux diffusions UDP **CAPTURO_DISCOVER** sur le port
**8081**. Capturo envoie cette requête sur le Wi-Fi et les interfaces USB,
puis propose directement l'adresse du téléphone.

## Interface

- **Démarrer / Arrêter la diffusion** : active ou coupe le serveur.
- **Aperçu** : image de la caméra sélectionnée, badge « En ligne ».
- **Caméra** : résolution, qualité JPEG, profil rapide, avant/arrière,
  images par seconde et torche.
- **Micro du téléphone** : flux `/audio`.
- **Apparence** : thème et couleur d'accent, appliqués immédiatement.
- **Adresses** : Wi-Fi et USB, avec copie, QR et rafraîchissement.
- **Accès au flux** : jeton optionnel, avec génération automatique.
- **Astuces** : USB, Wi-Fi, batterie.

## Bande passante

En MJPEG, la bande passante dépend de la scène et de la qualité JPEG.
Compter environ 0,5 à 1,5 Mo/s en 640 × 480, et davantage en 1080p/4K.
Pour réduire : baisser la résolution, choisir **Fluide · 70**, ou utiliser
l'USB.

## Notes

- Le port **8080** doit être libre sur le téléphone (vidéo et audio), et le
  port **8081** pour la découverte UDP.
- Avec un jeton, `/video` et `/audio` renvoient **401** sans
  `?token=…`. La page `/` reste accessible pour la détection.
- En cas de diffusion sur batterie, la veille partielle est limitée à six
  heures ; relancer la diffusion au-delà.
- Sur certains téléphones (HiOS/TECNO), désactiver l'optimisation de batterie
  pour « Capturo Webcam » afin que le service ne soit pas tué en
  arrière-plan.
- L'application n'enregistre rien sur le téléphone : elle ne fait que
  diffuser le flux.
- Si la caméra est occupée par une autre application, fermer celle-ci puis
  redémarrer la diffusion ; l'application retente automatiquement.

## Compilation

Projet **Android Studio** standard (Gradle). Ouvrir le dossier
`capturo-webcam` dans Android Studio, ou compiler en ligne de commande :

```bash
export JAVA_HOME=/chemin/vers/android-studio/jbr
./gradlew assembleDebug
# ou
./gradlew assembleRelease
```

Résultats :

```
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
```

Configuration actuelle :

- Kotlin + Jetpack Compose, Material 3
- AGP 9.4.1, plugin Compose 2.4.21, Gradle 9.6.0
- `compileSdk 37`, `minSdk 24`, `targetSdk 35`
- Version **2.1** (`versionCode 6`)
- SDK Android dans `~/Android/Sdk` (build-tools 36.0.0, plateforme 37)

## Signature

La clé `capturo-webcam.keystore` et son mot de passe ne sont **pas**
versionnés. Avant de construire l'APK, exporte le mot de passe :

```bash
export CAPTURO_KEYSTORE_PASS='ton-mot-de-passe'
./build.sh
```

La même clé signe les variantes debug et release ; la conserver pour les
mises à jour. Le chemin de la clé peut être changé avec
`CAPTURO_KEYSTORE`.

## Ancienne version Java

Les fichiers à la racine (`src/`, `res/`, `AndroidManifest.xml`,
`build.sh`, `audio-1.2b.pcm`) appartiennent à l'ancienne application Java
1.x. Le projet actif est le module Gradle `app/` en Kotlin/Compose ; ces
fichiers ne sont plus utilisés.

## Licence

Capturo Webcam est distribué sous **GNU GPL version 3 ou ultérieure**.
Voir le fichier [LICENSE](LICENSE).

# Distribution Android

## APK et signature

Le workflow **Build Android** produit un APK debug et son `SHA256SUMS`. Il exécute le lint,
les tests unitaires, compile aussi la release minifiée et peut lancer les tests sur
émulateurs Android 10 et 15. Le mode `variant=release` produit une APK signée avec votre clé.

Configurer ces secrets GitHub dans le dépôt :

- `NART_KEYSTORE_BASE64` : contenu du keystore encodé en base64 ;
- `NART_STORE_PASSWORD` : mot de passe du keystore ;
- `NART_KEY_ALIAS` : alias de la clé ;
- `NART_KEY_PASSWORD` : mot de passe de la clé.

Le workflow échoue explicitement si une valeur manque. Il ne crée pas de clé de distribution
jetable. Conserver la même clé hors du dépôt, avec une sauvegarde : une autre clé ne peut pas
mettre à jour une installation existante. Les APK debug des runners GitHub utilisent une clé
temporaire et ne constituent pas un canal de mises à jour stable.

En local, `android/keystore.properties` reste pris en charge. Une release sans signature est
refusée sauf avec `-PnartyaAllowUnsignedRelease=true`, réservé à la compilation de contrôle.
`-PnartyaQaRelease=true` signe la release minifiée avec la clé debug uniquement pour les tests
instrumentés ; cette APK n'est jamais publiée comme release par le workflow.

Augmenter `android/version.json.versionCode` à chaque distribution. Le test d'installation
sur émulateur construit deux APK debug avec la même clé et des codes croissants, puis installe
la seconde avec `adb install -r`, sans désinstallation. Cela vérifie la mise à niveau Android,
pas la publication d'une release sur votre infrastructure.

## Identité d'une version indépendante

Les valeurs restent celles de Nartya tant qu'elles ne sont pas configurées. Ne changer ni le
paquet ni le schéma pour une installation qui doit rester compatible avec la version d'origine.
Pour une application distincte, configurer ces **variables GitHub** :

| Variable | Défaut | Utilisation |
| --- | --- | --- |
| `NART_ANDROID_APPLICATION_ID` | `com.nartya.app` | Identifiant du paquet installé |
| `NART_ANDROID_APP_NAME` | `Nartya` | Nom affiché par Android |
| `NART_ANDROID_URL_SCHEME` | `nartya` | Deep links et retour OAuth |
| `NART_ANDROID_APP_LINK_HOST` | `nartya.app` | Domaine HTTPS des App Links et du site |

Le workflow synchronise le schéma et le site avec `VITE_APP_SCHEME` et `VITE_SITE_URL`.
En local, définir ces valeurs Vite dans `.env` et les variables natives dans le terminal avant
`npm run android:sync` et Gradle. Le namespace Java reste `com.nartya.app` : il n'est pas
nécessaire de renommer les classes pour changer l'applicationId.

Le catalogue et les comptes requièrent toujours des services compatibles : configurer
`VITE_API_URL`, `VITE_SUPABASE_URL`, `VITE_SUPABASE_ANON_KEY`, les URL OAuth autorisées côté
Supabase et les pages de retour/captcha sur votre domaine. Les icônes se remplacent dans
`android/app/src/main/res/mipmap-*` et `public/`.

## App Links vérifiés

Sur votre domaine, publier `https://VOTRE_DOMAINE/.well-known/assetlinks.json` avec le type
`application/json`, sans redirection et accessible publiquement. Exemple à adapter :

```json
[{
  "relation": ["delegate_permission/common.handle_all_urls"],
  "target": {
    "namespace": "android_app",
    "package_name": "VOTRE_APPLICATION_ID",
    "sha256_cert_fingerprints": ["EMPREINTE_SHA256_DU_CERTIFICAT_DE_SIGNATURE"]
  }
}]
```

L'empreinte est celle du certificat de signature, pas celle du fichier APK. La relever via
`apksigner verify --print-certs votre-release.apk`. Vérifier ensuite sur appareil :

```bash
adb shell pm verify-app-links --re-verify VOTRE_APPLICATION_ID
adb shell pm get-app-links VOTRE_APPLICATION_ID
```

Le dépôt ne peut pas publier ce fichier sur un domaine externe. Le schéma personnalisé reste
un repli OAuth ; la vérification App Links exige votre domaine et votre certificat.

## Téléchargements et limites de reprise

La pause conserve les fichiers partiels. Les URL signées restent uniquement en mémoire : après
la destruction du processus, les transferts actifs deviennent interrompus et l'interface
obtient une source fraîche au retour. Les pauses volontaires restent en pause. La notification
« Reprendre dans l'app » ouvre la bibliothèque et relance la résolution des sources.

Le Wi-Fi uniquement exige un Wi-Fi validé et non limité. Les transferts suspendus pour cette
raison reprennent lorsque cette connexion revient et que l’application est visible ; les
sources sont résolues à nouveau. Cela évite un démarrage de service interdit depuis l’arrière-plan. Un arrêt
forcé Android ne permet pas de promettre une reprise automatique sans réouverture de l'app.
Le timeout `dataSync` d'Android 15+ arrête proprement le service et conserve l'état.

Le quota inclut les fichiers partiels ; les vérifications sont espacées d'une seconde pour
éviter un parcours disque par bloc. Quelques blocs en cours peuvent dépasser légèrement le
quota. Une réserve de 100 Mo protège le stockage du téléphone. Baisser le quota n'efface aucun
fichier ; le nettoyage des épisodes terminés exige une confirmation et un compte connecté.

Les MP4 ne reprennent un préfixe que si l’URL source, un ETag fort et les plages concordent. Sans validateur
fiable, ils redémarrent pour éviter d'assembler deux fichiers différents. Le mode parallèle
utilise un fichier distinct et le supprime s'il échoue. Un changement de playlist HLS invalide
les fragments précédents ; les clés et initialisations multiples sont conservées séparément.
Les playlists HLS par plages (`EXT-X-BYTERANGE`) sont refusées explicitement par le téléchargement.

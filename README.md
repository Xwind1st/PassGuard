# PassGuard

Application Android qui montre en temps réel comment ton forfait de données mobiles se vide, même en arrière-plan, avec un suivi par application, un complément de forfait et une interface en français ou en anglais.

## Compiler avec GitHub (gratuit)

1. Envoie le code sur le dépôt GitHub (branche `main`), **y compris le dossier `.github`**.
2. Ouvre l’onglet **Actions** : la compilation « Build APK » démarre toute seule.
3. Quand elle est **verte**, ouvre le run et télécharge l’artefact **PassGuard-debug-apk**.
4. Dézippe, copie `app-debug.apk` sur le téléphone, autorise l’installation depuis cette source, puis installe.

## Premier lancement

1. Autorise l’accès à l’utilisation pour PassGuard.
2. Fixe ton forfait : quantité (Mo/Go) et durée en **jours + heures en même temps**.
3. Laisse la notification active. Sur Tecno, Infinix ou Xiaomi, va dans l’espace personnel pour retirer PassGuard des économies de batterie.

## Versions

### v0.2 (actuelle)

- Durée de validité : **jours et heures simultanément** (plus le choix exclusif heure *ou* jour).
- Même logique dans « Compléter » pour prolonger la durée.
- Exemple : il te reste 1,2 Go jusqu’au 28 à 15 h → saisis la quantité, puis les jours et heures restants jusqu’à cette échéance.

### v0.1

- Réservoir, débit 3–5 s, notification 1 s / 15 s, alertes 30/50/80/90/100 %, Compléter, Qui consomme, Espace perso FR/EN, relance auto/manuelle.

## Feuille de route (décalée)

| Version | Contenu |
|--------|---------|
| **v0.2** | Jours + heures ensemble pour la validité (fait) |
| **v0.3** | Ajustements UX et compatibilité constructeurs (ex-v0.2) |
| **v0.4** | Écran détaillé « Répartition » par source (ex-v0.3) |
| **v0.5** | Fenêtre flottante + section « Pendant que le téléphone était posé » (ex-v0.4) |
| À décider | Alerte consommation inhabituelle du partage de connexion |
| Publication | Fiche Play Store, confidentialité, test fermé |

## Limites

- Le total vient du compteur direct du téléphone. Le détail par application peut être décalé ; l’écart apparaît sous « Non attribué / système ».
- 1 Go = 1024 Mo dans l’app (binaire). Les opérateurs comptent souvent en 1000 Mo.
- Le partage de connexion est un seul bloc.

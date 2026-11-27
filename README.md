# PassGuard

Application Android qui montre en temps réel comment ton forfait de données mobiles se vide, même en arrière-plan, avec un suivi par application, un complément de forfait et une interface en français ou en anglais.

## Compiler avec GitHub (gratuit)

1. Envoie le code sur le dépôt GitHub (branche `main`), **y compris le dossier `.github`**.
2. Ouvre l’onglet **Actions** : la compilation « Build APK » démarre toute seule.
3. Quand elle est **verte**, ouvre le run et télécharge l’artefact **PassGuard-debug-apk**.
4. Dézippe, copie `app-debug.apk` sur le téléphone, autorise l’installation depuis cette source, puis installe.

## Premier lancement

1. Autorise l’accès à l’utilisation pour PassGuard.
2. Fixe ton forfait : quantité (Mo/Go) et **date + heure de fin** (calendrier et horloge).
3. Laisse la notification active. Sur Tecno, Infinix ou Xiaomi, va dans l’espace personnel pour retirer PassGuard des économies de batterie.

## Versions

### v0.3 (actuelle)

- Fin de forfait par **calendrier + horloge** : jour, heure, minutes et secondes.
- Tu consultes le délai chez l’opérateur (ex. 28 novembre à 15 h), tu le saisis tel quel dans PassGuard.
- **Compléter** : prolongation optionnelle en jours, heures, minutes et secondes.

### v0.2

- Durée relative jours + heures simultanément (remplacée en saisie principale par le calendrier en v0.3).

### v0.1

- Réservoir, débit, notification, alertes 30/50/80/90/100 %, Compléter, Qui consomme, Espace perso FR/EN.

## Feuille de route (décalée)

| Version | Contenu |
|--------|---------|
| **v0.3** | Calendrier + horloge pour la date de fin (fait) |
| **v0.4** | Ajustements UX et compatibilité constructeurs |
| **v0.5** | Écran détaillé « Répartition » par source |
| **v0.6** | Fenêtre flottante + section « Pendant que le téléphone était posé » |
| À décider | Alerte consommation inhabituelle du partage de connexion |
| Publication | Fiche Play Store, confidentialité, test fermé |

## Limites

- Le total vient du compteur direct du téléphone. Le détail par application peut être décalé ; l’écart apparaît sous « Non attribué / système ».
- 1 Go = 1024 Mo dans l’app (binaire). Les opérateurs comptent souvent en 1000 Mo.
- Le partage de connexion est un seul bloc.

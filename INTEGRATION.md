# RaskolVault — Руководство по интеграции (1.2.0)

Документ для разработчиков плагинов-друзей: **RaskolMarket, RaskolCaravans, RaskolCharters, EconomyShopGUI, ChestShop**.

---

## 1. ПОДКЛЮЧЕНИЕ К RASKOLVAULT

### 1.1 Maven-зависимость

RaskolVault **не публикуется** в публичный Maven-репозиторий. Два варианта подключения:

**Вариант A (рекомендуемый): локальный install**
```bash
# Скопируйте raskol-vault-1.2.0-SNAPSHOT.jar в локальный Maven:
mvn install:install-file \
  -Dfile=raskol-vault-1.2.0-SNAPSHOT.jar \
  -DgroupId=dev.raskol \
  -DartifactId=raskol-vault \
  -Dversion=1.2.0-SNAPSHOT \
  -Dpackaging=jar

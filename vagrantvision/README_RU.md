# VagrantVision 1.0.0
Клиентский аддон Forge 1.12.2 / SrpCotesia 1.4.0-bleedingedge.3. Автоматическая красно-фиолетовая постобработка игрового кадра паразита перед HUD, GLSL 1.20.
Сборка: JDK 8, Gradle 4.10.3, gradle setupDecompWorkspace build. При наличии оригинального SrpCotesia JAR в libs/ используется он; CI компилирует против проверенной сигнатуры API, не включая её в итоговый JAR. Для игры нужен оригинальный SrpCotesia.
Объёмный туман по глубине, подсветка сущностей и интеграция с активным OptiFine Shader Pipeline не реализованы.

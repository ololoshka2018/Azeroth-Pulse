# Сторонние ресурсы и обозначения

MIT в корне проекта распространяется на оригинальный код Azeroth Pulse. У перечисленных ниже ресурсов действуют условия соответствующих правообладателей.

## Интерфейс и игровые данные Blizzard

Графика, игровые значки, атласы, карта, шрифт Morpheus, названия и материалы World of Warcraft принадлежат соответствующим правообладателям Blizzard Entertainment. Они исключены из лицензии MIT проекта. Атрибуция сохраняет указание авторства, а разрешение на дальнейшее использование этих материалов определяется условиями правообладателя.

В комплект входят файлы из `app/src/main/assets/ui/classic/`, игровые значки приложения из `app/src/main/res/drawable-nodpi/` и данные талантов из `app/src/main/assets/ui/talents-data.json`. История происхождения и операций над текстурами находится в `development/classic-assets.json`.

Публичные архивы текстур, использованные при разработке, приведены в таблице.

| Архив | Зафиксированная ревизия |
| --- | --- |
| NSO73/WoW-Vanilla-Addon-Resources | 42bbe067f2d0054477927e156c2342ddc73f2f60 |
| Gethe/wow-ui-textures | 312a6e61bab69370c65ccc0a08f1075d62196294 |

Azeroth Pulse — независимый фанатский проект. World of Warcraft и Blizzard Entertainment являются товарными знаками Blizzard Entertainment. Публикация приложения не означает одобрение со стороны этих компаний.

Условия использования материалов и обозначений Blizzard опубликованы правообладателем. [Blizzard Legal FAQ](https://www.blizzard.com/en-us/legal/28d5ebbf-c245-4408-8ba9-043dd5f056bf/legal-faq).

## Источники данных

Приложение читает публичные страницы и API перечисленных проектов. Авторы соответствующих материалов сохраняют свои права.

| Раздел | Проекты |
| --- | --- |
| Перепись и популярность | WoWCensus |
| Деревья талантов | Armory WoW Forever |
| Билды | Mobalytics, MmonsteR, METAMMO |
| Новости | Blizzard Entertainment |

Русские краткие изложения в начальной подборке созданы для приложения. Полные статьи подгружаются с сайта Blizzard при чтении и сохраняются в локальном кэше.

## Программные компоненты

| Компонент | Условия |
| --- | --- |
| jsoup 1.21.2 | MIT. Копия лицензии включена в `app/src/main/assets/LICENSE-jsoup.txt` |
| Gradle Wrapper 8.9 | Apache License 2.0. Копия в `gradle/LICENSE.txt` |
| Google ML Kit Translation 17.0.3 | Условия Google ML Kit и требования атрибуции Google Translate |
| AndroidX и транзитивные библиотеки | Лицензии соответствующих компонентов и метаданные зависимостей |

Перевод выполняется средствами Google Translate через ML Kit. [Условия ML Kit](https://developers.google.com/ml-kit/terms) · [Требования к переводу](https://developers.google.com/ml-kit/language/translation/translation-terms).

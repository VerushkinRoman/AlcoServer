# Архитектурный Шаблон для KMP-проектов (Clean Architecture + MVI)

Универсальный гайд по архитектуре для всех Kotlin Multiplatform проектов. Содержит описание слоев, паттернов, соглашений и полные примеры кода ключевых компонентов.

---

## 1. Общая Архитектура

Проекты строятся на **Clean Architecture** с **MVI (Model-View-Intent)** в слое презентации. Это обеспечивает тестируемость, независимость от фреймворков и четкое разделение ответственности.

```
┌─────────────────────────────────────────────────────┐
│                  Presentation (UI)                   │
│  ViewModel / State / Event / Action / @Composable    │
├─────────────────────────────────────────────────────┤
│                    Domain (Бизнес-логика)             │
│  Repository (интерфейсы) / Model / UseCase / Error   │
├─────────────────────────────────────────────────────┤
│                   Data (Реализации)                  │
│  RepositoryImpl / DataSource / DTO / Mappers         │
├─────────────────────────────────────────────────────┤
│                Common / Core (Инфраструктура)        │
│  DI / Platform / Utils / Compose-компоненты          │
└─────────────────────────────────────────────────────┘
```

### Слои (сверху вниз):

**1. Presentation (UI)** — `feature_xxx/compose/` + `feature_xxx/presentation/`
- **Назначение:** Отображение UI, обработка пользовательского ввода.
- **Компоненты:** `BaseSharedViewModel<State, Action, Event>`, `State` (data class), `Event` (sealed interface), `Action` (sealed interface), `@Composable` функции.
- **Правила:** Содержит только логику UI. Взаимодействует с Domain через репозитории или Use Case'ы.
- **Зависимости:** Только от Domain слоя (через интерфейсы).

**2. Domain (Бизнес-логика)** — `feature_xxx/domain/`
- **Назначение:** Бизнес-правила и логика приложения, независимые от фреймворков.
- **Компоненты:** `Repository` (интерфейсы), `UseCase`, `Model` (бизнес-сущности), `Error`, `Dispatchers`.
- **Правила:** Не зависит от Presentation и Data слоев. Не имеет зависимостей на Android/iOS/Ktor/Firebase.

**3. Data** — `feature_xxx/data/`
- **Назначение:** Реализация репозиториев, работа с внешними источниками данных.
- **Компоненты:** `RepositoryImpl`, `DataSource` (Online/Offline), `DTO`, мапперы.
- **Правила:** Реализует интерфейсы из Domain. Маппит DTO <-> Model. Содержит платформенные реализации.

**4. Common / Core** — `common/`
- **Назначение:** Вспомогательные компоненты для всех слоев.
- **Компоненты:** `di/` (DI), `core/platform/` (expect/actual), `presentation/utils/` (BaseSharedViewModel), `domain/utils/` (Result, Error, Dispatchers), `compose/` (общие UI).

---

## 2. Ключевые Компоненты (Полные Примеры)

### 2.1. Result и Error — система обработки ошибок

```
common/domain/utils/
├── Error.kt           # Маркерный интерфейс для ошибок
├── Result.kt          # sealed interface Result<D, E>
├── DomainError.kt     # (опционально) Enum общих ошибок
└── Dispatchers.kt     # Абстракция корутинных диспетчеров
```

#### Error.kt
```kotlin
package com.yourapp.common.domain.utils

interface Error
```

Каждая фича определяет свои ошибки через enum, реализующий `Error`:
```kotlin
package com.yourapp.feature_settings.domain.utils

enum class SettingsError : Error {
    ClearDataError,
    SaveNameError,
    FetchSettingsError,
}
```

#### Result.kt
```kotlin
package com.yourapp.common.domain.utils

typealias DomainError = Error

sealed interface Result<out D, out E : Error> {
    data class Success<out D>(val data: D) : Result<D, Nothing>
    data class Error<out E : DomainError>(val error: E) : Result<Nothing, E>
}

// Трансформация данных внутри Result
inline fun <T, E : Error, R> Result<T, E>.map(map: (T) -> R): Result<R, E> {
    return when (this) {
        is Result.Error -> Result.Error(error)
        is Result.Success -> Result.Success(map(data))
    }
}

// Успешный результат без данных (Unit)
val EmptySuccessResult = Result.Success(Unit)

// Конвертация в EmptyResult
fun <T, E : Error> Result<T, E>.asEmptyDataResult(): EmptyResult<E> {
    return map { }
}

// Обработка успеха
inline fun <T, E : Error> Result<T, E>.onSuccess(action: (T) -> Unit): Result<T, E> {
    return when (this) {
        is Result.Error -> this
        is Result.Success -> {
            action(data)
            this
        }
    }
}

// Обработка ошибки
inline fun <T, E : Error> Result<T, E>.onError(action: (E) -> Unit): Result<T, E> {
    return when (this) {
        is Result.Success -> this
        is Result.Error -> {
            action(error)
            this
        }
    }
}

// Получение данных или null
fun <T> Result<T, Error>.getOrNull(): T? = when (this) {
    is Result.Success -> data
    is Result.Error -> null
}

// Псевдоним для Result без данных
typealias EmptyResult<E> = Result<Unit, E>
```

**Правила работы с Result:**
- Все методы в Domain и Data слоях возвращают `Result<T, Error>` или `EmptyResult<Error>`.
- В ViewModel результат обрабатывается через `.onSuccess { }` / `.onError { }`.
- Для трансформации данных используется `.map { }`.
- Для игнорирования данных — `.asEmptyDataResult()`.

---

### 2.2. Dispatchers — абстракция корутинных диспетчеров

```kotlin
package com.yourapp.common.domain.utils

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.IO

interface Dispatchers {
    val main: CoroutineDispatcher
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher
    val unconfined: CoroutineDispatcher
}

class DispatchersImpl : Dispatchers {
    override val main: CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Main
    override val io: CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO
    override val default: CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default
    override val unconfined: CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
}
```

**Правила:**
- `Dispatchers` внедряется в репозитории, датасурсы и Use Case'ы через конструктор.
- Все IO-операции оборачиваются в `withContext(dispatchers.io) { ... }` для тестируемости и переносимости.
- Регистрируется как `bindSingleton` в CommonDomainModule.

---

### 2.3. BaseSharedViewModel — MVI-база для всех ViewModel

```
common/presentation/utils/
├── BaseSharedViewModel.kt
└── PlatformViewModelProvider.kt (expect/actual)
```

```kotlin
package com.yourapp.common.presentation.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

abstract class BaseSharedViewModel<State : Any, Action, Event>(initialState: State) : ViewModel() {
    // StateFlow — постоянное состояние экрана
    private val _viewStates = MutableStateFlow(initialState)
    fun viewStates() = _viewStates.asStateFlow()

    // SharedFlow (replay=1) — одноразовые действия (snackbar, навигация)
    private val _viewActions = MutableSharedFlow<Action?>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    fun viewActions() = _viewActions.asSharedFlow()

    // Сеттер/геттер для state (viewState = viewState.copy(...))
    protected var viewState: State
        get() = _viewStates.value
        set(value) {
            _viewStates.value = value
        }

    // Сеттер/геттер для action (viewAction = SomeAction)
    protected var viewAction: Action?
        get() = _viewActions.replayCache.last()
        set(value) {
            _viewActions.tryEmit(value)
        }

    // Точка входа для всех событий от UI
    abstract fun obtainEvent(viewEvent: Event)

    // Запуск корутины в viewModelScope
    protected fun withViewModelScope(block: suspend CoroutineScope.() -> Unit): Job {
        return viewModelScope.launch(block = block)
    }
}

// Compose-расширение для подписки на Action
@Composable
fun <T : R, R> SharedFlow<T>.collectAsStateWithLifecycle(
    context: CoroutineContext = EmptyCoroutineContext,
): State<R?> {
    return collectAsState(initial = null, context = context)
}
```

#### PlatformViewModelProvider.kt
```kotlin
package com.yourapp.common.presentation.utils

import androidx.lifecycle.ViewModelProvider

expect fun getPlatformFactory(): ViewModelProvider.Factory?
```
Для Android/iOS возвращает `null`, для JVM — фабрику через рефлексию (если нужно).

---

### 2.4. Inject — обертка для Kodein-DI

```kotlin
package com.yourapp.common.core.di

import org.kodein.di.DirectDI
import org.kodein.di.instance

object Inject {
    private var _di: DirectDI? = null

    val di: DirectDI
        get() = requireNotNull(_di)

    fun createDependencies(tree: DirectDI) {
        _di = tree
    }

    fun isInitialized(): Boolean = _di != null

    inline fun <reified T> instance(tag: Any? = null): T {
        return di.instance(tag)
    }
}
```

`PlatformSDK` инициализирует DI при старте приложения:
```kotlin
object PlatformSDK {
    fun init(configuration: PlatformConfiguration) {
        val umbrellaModule = DI.Module("umbrellaModule") {
            bindSingleton<PlatformConfiguration> { configuration }
        }

        Inject.createDependencies(
            DI {
                importAll(
                    umbrellaModule,
                    platformModule,
                    commonDomainModule,
                    // ... остальные модули
                )
            }.direct
        )
    }
}

internal expect val platformModule: DI.Module
```

---

### 2.5. PlatformConfiguration — expect/actual для платформенного кода

```kotlin
package com.yourapp.common.core.platform

// commonMain
expect class PlatformConfiguration {
    val isDebug: Boolean
    // Добавляй платформенные зависимости по необходимости
}

// androidMain
actual class PlatformConfiguration(
    val androidContext: Context,
    // ... другие Android-специфичные зависимости
    actual val isDebug: Boolean,
)
```

**Правила:**
- `PlatformConfiguration` — единая точка входа платформенных зависимостей.
- Регистрируется как `bindSingleton` в `PlatformSDK.init()`.
- Все `expect`/`actual` объявления минимальны, без дублирования логики.
- Для каждого `actual` есть отдельный файл: `Name.android.kt`, `Name.ios.kt`, `Name.jvm.kt`.

---

## 3. Иерархия Пакетов (Feature-first)

Каждая фича содержит все слои внутри одного пакета. `compose/`, `presentation/`, `domain/` и `data/` — на одном уровне:

```
feature_<name>/
├── compose/                    # @Composable экраны и UI-компоненты
│   ├── <Name>Screen.kt
│   └── components/
├── presentation/               # MVI компоненты (ViewModel, State, Event, Action)
│   ├── <Name>ViewModel.kt
│   ├── di/                     # DI модуль для ViewModel
│   └── models/
│       ├── <Name>State.kt
│       ├── <Name>Event.kt
│       └── <Name>Action.kt (если требуется)
├── domain/                     # Domain слой (бизнес-логика)
│   ├── repository/
│   │   └── <Name>Repository.kt (интерфейс)
│   ├── models/                 # Бизнес-модели фичи
│   ├── use_cases/              # (опционально)
│   ├── utils/
│   │   └── <Name>Error.kt
│   └── di/                     # DI модуль Domain
└── data/                       # Data слой (реализации)
    ├── datasource/
    │   ├── <Name>DataSource.kt (интерфейс)
    │   └── <Name>DataSourceImpl.kt
    ├── models/                  # DTO фичи
    └── di/                      # DI модуль Data
```

Общий код (не привязанный к фичам) — в пакете `common/`:
```
common/
├── calendar/                    # (по желанию) переиспользуемые модули
├── compose/
│   ├── components/              # Общие UI-компоненты
│   └── utils/
├── core/
│   ├── di/Inject.kt             # Kodein-обертка
│   └── platform/
│       ├── PlatformConfiguration.kt
│       └── PlatformSDK.kt
├── data/
│   ├── datasource/              # OnlineDataSource, OfflineDataSource
│   ├── di/CommonDataModule.kt   # Firebase, Ktor, Settings
│   └── models/                  # Общие DTO
├── domain/
│   ├── di/CommonDomainModule.kt # Repositories, Dispatchers
│   ├── models/                  # Бизнес-сущности
│   ├── repository/              # AuthRepository, SettingsRepository и т.д.
│   └── utils/
│       ├── Dispatchers.kt
│       ├── Error.kt
│       ├── Result.kt
│       └── DateTimeUtils.kt (пример утилиты)
├── presentation/
│   └── utils/
│       ├── BaseSharedViewModel.kt
│       └── PlatformViewModelProvider.kt
└── utils/                       # Прочие утилиты
```

---

## 4. MVI-паттерн через BaseSharedViewModel

### State
Неизменяемый `data class`, описывающий ВСЁ состояние экрана.

```kotlin
data class SettingsScreenState(
    val currentName: String = "",
    val currentEmail: String = "",
    val isLoading: Boolean = false,
    val isDialogVisible: Boolean = false,
) : Any  // State : Any — обязательное ограничение
```

### Event
`sealed interface` для событий от UI (в прошедшем времени).

```kotlin
sealed interface SettingsScreenEvent {
    data object ClearAction : SettingsScreenEvent
    data object OnLogoutClick : SettingsScreenEvent
    data class OnNameChanged(val name: String) : SettingsScreenEvent
    data class OnGoogleLoginResult(val idToken: String?) : SettingsScreenEvent
}
```

### Action
`sealed interface` для одноразовых действий (показать Snackbar, навигация). Отправляется через `viewAction`.

```kotlin
sealed interface SettingsScreenAction {
    data class ShowError(val error: Error) : SettingsScreenAction
    data object NavigateToLogin : SettingsScreenAction
}
```

### Полный поток:
```
UI ──(Event)──> ViewModel.obtainEvent()
                    │
                    ├── viewState = viewState.copy(...)   // обновление состояния
                    ├── withViewModelScope {               // асинхронная операция
                    │     repository.method()
                    │         .onSuccess { viewState = ... }
                    │         .onError { viewAction = ShowError(it) }
                    │}
                    │
                    ├── viewState ──(StateFlow)──> UI (рекомпозиция)
                    └── viewAction ──(SharedFlow)──> UI (LaunchedEffect)
```

### Пример ViewModel:
```kotlin
class SettingsScreenViewModel(
    private val settingsRepository: SettingsScreenRepository,
    private val authRepository: AuthRepository,
    private val dispatchers: Dispatchers,
) : BaseSharedViewModel<SettingsScreenState, SettingsScreenAction, SettingsScreenEvent>(
    initialState = SettingsScreenState()
) {
    override fun obtainEvent(viewEvent: SettingsScreenEvent) {
        when (viewEvent) {
            SettingsScreenEvent.ClearAction -> viewAction = null
            SettingsScreenEvent.OnLogoutClick -> onLogout()
            is SettingsScreenEvent.OnNameChanged -> onNameChanged(viewEvent.name)
            is SettingsScreenEvent.OnGoogleLoginResult -> onLogin(viewEvent.idToken)
        }
    }

    fun onLogout() {
        withViewModelScope {
            authRepository.logout()
                .onSuccess { viewAction = SettingsScreenAction.NavigateToLogin }
                .onError { viewAction = SettingsScreenAction.ShowError(it) }
        }
    }

    private fun onNameChanged(name: String) {
        viewState = viewState.copy(currentName = name)
        withViewModelScope {
            settingsRepository.saveName(name)
                .onError { viewAction = SettingsScreenAction.ShowError(it) }
        }
    }
}
```

### Пример Compose-экрана:
```kotlin
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val viewModel: SettingsScreenViewModel by rememberViewModel()
    val state by viewModel.viewStates().collectAsStateWithLifecycle()
    val action by viewModel.viewActions().collectAsStateWithLifecycle()

    LaunchedEffect(action) {
        action?.let {
            when (it) {
                is SettingsScreenAction.NavigateToLogin -> { /* навигация */ }
                is SettingsScreenAction.ShowError -> { /* показать ошибку */ }
            }
            viewModel.obtainEvent(SettingsScreenEvent.ClearAction)
        }
    }

    SettingsScreenContent(
        state = state,
        onEvent = viewModel::obtainEvent,
    )
}

@Composable
private fun SettingsScreenContent(
    state: SettingsScreenState,
    onEvent: (SettingsScreenEvent) -> Unit,
) {
    // UI без доступа к ViewModel
}
```

**Правила MVI:**
- Вся логика — внутри `obtainEvent()`.
- `viewState = viewState.copy(...)` для изменения состояния (иммутабельность).
- `viewAction = SomeAction(...)` для одноразовых действий.
- После обработки `Action` вызвать `obtainEvent(ClearAction)`.
- `withViewModelScope { }` для асинхронных операций.
- Compose-функции не имеют доступа к ViewModel — только `state` + `onEvent`.

---

## 5. Репозитории и DataSource

### Правила:
- **Репозиторий (в Domain):** Интерфейс, определяющий доступные данные/операции.
- **DataSource (в Data):** Интерфейс + реализация для конкретного источника (Online/Offline).
- **Impl Репозитория (в Data):** Принимает DataSource'ы и `Dispatchers`, реализует логику (кэширование, выбор источника).

### Пример Domain (интерфейс):
```kotlin
interface AuthRepository {
    suspend fun login(idToken: String?): Result<LoggedUser, Error>
    suspend fun logout(): EmptyResult<Error>
    suspend fun loginAnonymously(): EmptyResult<Error>
    suspend fun getCurrentLoggedUserFlow(): Flow<Result<LoggedUser, Error>>
    fun getCurrentUser(): Result<LoggedUser?, Error>
}
```

### Пример Data (реализация):
```kotlin
class AuthRepositoryImpl(
    private val onlineDataSource: OnlineDataSource,
    private val offlineDataSource: OfflineDataSource,
    private val dispatchers: Dispatchers,
) : AuthRepository {

    override suspend fun login(idToken: String?): Result<LoggedUser, Error> =
        withContext(dispatchers.io) {
            onlineDataSource.login(idToken)
                .map { dataUser -> dataUser.toLoggedUser() }
                .onSuccess { loggedUser ->
                    offlineDataSource.setUserMail(loggedUser.mail)
                }
        }

    override fun getCurrentUser(): Result<LoggedUser?, Error> {
        return offlineDataSource.getUserMail()?.let { mail ->
            Result.Success(LoggedUser(mail = mail))
        } ?: Result.Success(null)
    }
}
```

### Пример DataSource (Online):
```kotlin
interface OnlineDataSource {
    suspend fun login(idToken: String?): Result<DataLoggedUser, Error>
    suspend fun getAllUsers(): Result<Map<String, DataUser>, Error>
}

class OnlineDataSourceImpl(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth,
) : OnlineDataSource {
    override suspend fun login(idToken: String?): Result<DataLoggedUser, Error> {
        return try {
            // ... работа с Firebase
            Result.Success(dataUser)
        } catch (_: Exception) {
            Result.Error(DomainErrorType.LoginError)
        }
    }
}
```

### Пример DataSource (Offline):
```kotlin
interface OfflineDataSource {
    fun getUserMail(): String?
    fun setUserMail(mail: String)
}

class OfflineDataSourceImpl(
    private val settings: Settings,  // com.russhwolf.settings
) : OfflineDataSource {
    override fun getUserMail(): String? = settings.getStringOrNull(KEY_USER_MAIL)
    override fun setUserMail(mail: String) = settings.putString(KEY_USER_MAIL, mail)

    companion object {
        private const val KEY_USER_MAIL = "user_mail"
    }
}
```

### Маппинг DTO <-> Model:
```kotlin
// Data -> Domain
fun DataLoggedUser.toLoggedUser(): LoggedUser = LoggedUser(
    mail = email,
    name = name ?: email.substringBefore("@"),
)

// Domain -> Data
fun LoggedUser.toDataLoggedUser(): DataLoggedUser = DataLoggedUser(
    email = mail,
    name = name,
)
```
Мапперы — extension-функции в том же пакете, что и DTO.

---

## 6. UseCase'ы (опционально)

Если логика переиспользуется в нескольких ViewModel или слишком сложна для репозитория.

```kotlin
class CalculateStatistic {
    operator fun invoke(
        calendarData: List<MonthData>,
        inactivePeriods: List<InactiveDates> = emptyList(),
    ): StatisticWithDaysState {
        // ... чистая бизнес-логика без副作用
    }
}
```

```kotlin
class GetContacts(
    private val repository: ShareRepository,
) {
    suspend operator fun invoke(mail: String): Result<ContactsResult, Error> = coroutineScope {
        val cloudDeferred = async { repository.getContactsFromCloud(mail) }
        val usersDeferred = async { repository.getAllUsers() }

        val cloudResult = cloudDeferred.await()
        val usersResult = usersDeferred.await()

        // Комбинирование результатов из разных источников
        return@coroutineScope when {
            cloudResult is Result.Error -> cloudResult
            usersResult is Result.Error -> usersResult
            else -> Result.Success(combine(...))
        }
    }
}
```

**Правила:**
- UseCase — класс с `operator fun invoke()` (один публичный метод).
- UseCase может зависеть только от репозиториев (интерфейсов).
- Регистрируется в DI модуле через `bindProvider`/`bindSingleton`.

---

## 7. DI (Kodein) — организация модулей

### 7.1. CommonDomainModule (глобальные зависимости)
```kotlin
val commonDomainModule = DI.Module("CommonDomainModule") {
    bindSingleton<Dispatchers> { DispatchersImpl() }

    bindSingleton<CommonSettingsRepository> {
        CommonSettingsRepositoryImpl(
            offlineDataSource = instance(),
            onlineDataSource = instance(),
            authRepository = instance(),
            dispatchers = instance(),
        )
    }

    bindProvider<AuthRepository> {
        AuthRepositoryImpl(
            onlineDataSource = instance(),
            offlineDataSource = instance(),
            dispatchers = instance(),
        )
    }
}
```

### 7.2. CommonDataModule (Firebase, Ktor, настройки)
```kotlin
val commonDataModule = DI.Module("CommonDataModule") {
    bindSingleton<Settings> { Settings() }
    bindSingleton<FirebaseFirestore> { Firebase.firestore }
    bindSingleton<FirebaseAuth> { Firebase.auth }

    bindSingleton<HttpClient> {
        HttpClient {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            defaultRequest { contentType(ContentType.Application.Json) }
        }
    }

    bindProvider<OfflineDataSource> { OfflineDataSourceImpl(settings = instance()) }
    bindSingleton<OnlineDataSource> { OnlineDataSourceImpl(firestore = instance(), auth = instance()) }
}
```

### 7.3. Feature DataModule
```kotlin
val myCalendarDataModule = DI.Module("MyCalendarDataModule") {
    bindProvider<MyCalendarOnlineDataSource> {
        MyCalendarOnlineDataSourceImpl(firestore = instance())
    }
}
```

### 7.4. Feature DomainModule
```kotlin
val myCalendarDomainModule = DI.Module("MyCalendarDomainModule") {
    bindProvider<MyCalendarRepository> {
        MyCalendarRepositoryImpl(
            myCalendarOnlineDataSource = instance(),
            onlineDataSource = instance(),
            shareOnlineDatasource = instance(),  // Можно брать из Inject.instance()
            dispatchers = instance(),
        )
    }
}
```

### 7.5. Feature ViewModelModule
```kotlin
val myCalendarViewModelModule = DI.Module("MyCalendarViewModelModule") {
    bindProvider {
        MyCalendarViewModel(
            getStartCalendarData = instance(),
            myCalculateStatistic = instance(),
            calculateMonthIndex = instance(),
            commonCalendarRepository = instance(),
            myCalendarRepository = instance(),
            friendsRepository = instance(),
            notificationManager = instance(),
            platform = instance(),
        )
    }
}
```

### 7.6. Порядок импорта модулей в PlatformSDK
```kotlin
Inject.createDependencies(
    DI {
        importAll(
            // 1. Platform
            umbrellaModule,        // PlatformConfiguration
            platformModule,        // platformModule (expect/actual)

            // 2. Domain (базовые зависимости — Dispatchers, Repositories)
            commonDomainModule,
            navigationDomainModule,
            settingsDomainModule,
            myCalendarDomainModule,

            // 3. Data (DataSource'ы)
            commonDataModule,
            settingsScreenDataModule,
            myCalendarDataModule,

            // 4. Presentation (ViewModel'ы)
            myCalendarViewModelModule,
            settingsScreenViewModelModule,
        )
    }.direct
)
```

**Правила DI:**
- `bindSingleton` — для тяжелых объектов (Dispatchers, Firebase, HttpClient).
- `bindProvider` — для объектов, которые могут иметь состояние (ViewModel, Repository).
- `instance()` — получение зависимости из графа.
- `Inject.instance()` — получение зависимости в статическом контексте (вне DI-модуля).
- Нельзя создавать циклические зависимости.

---

## 8. Compose UI — правила

### Структура экрана:
```kotlin
@Composable
fun SomeScreen(modifier: Modifier = Modifier) {
    val viewModel: SomeViewModel by rememberViewModel()
    val state by viewModel.viewStates().collectAsStateWithLifecycle()
    val action by viewModel.viewActions().collectAsStateWithLifecycle()

    LaunchedEffect(action) {
        action?.let {
            when (it) {
                // обработка одноразовых действий
            }
            viewModel.obtainEvent(SomeEvent.ClearAction)
        }
    }

    SomeScreenContent(state = state, onEvent = viewModel::obtainEvent)
}

@Composable
private fun SomeScreenContent(
    state: SomeState,
    onEvent: (SomeEvent) -> Unit,
) {
    // UI без доступа к ViewModel
    // Только state + лямбды
}
```

### State Hoisting:
- Компоненты получают `State` и лямбды для событий, а не ViewModel.
- Нет доступа к ViewModel в `@Composable` функциях ниже уровня экрана.

### Ресурсы:
- Используй `org.jetbrains.compose.resources`: `stringResource()`, `painterResource()`.
- Не используй Android `R.` или iOS-ресурсы напрямую.

### Общие компоненты:
- Если UI-компонент используется на нескольких экранах — вынеси в `common/compose/components/`.

---

## 9. Платформенная интеграция (expect/actual)

```
composeApp/src/
├── commonMain/kotlin/.../common/core/platform/
│   ├── PlatformConfiguration.kt    // expect class
│   └── PlatformSDK.kt              // expect + объект инициализации
├── androidMain/kotlin/.../
│   └── PlatformConfiguration.android.kt  // actual class
├── iosMain/kotlin/.../
│   └── PlatformConfiguration.ios.kt      // actual class
└── jvmMain/kotlin/.../
    └── PlatformConfiguration.jvm.kt      // actual class
```

- `expect` объявляет минимум — только то, что реально различается между платформами.
- `actual` файлы именуются по шаблону: `ИмяФайла.платформа.kt`.
- Для каждого `expect` должно быть ровно 3 `actual` (androidMain, iosMain, jvmMain).

---

## 10. Gradle-сборка

### Версии зависимостей — `libs.versions.toml` (Version Catalog):
- Единый источник истины для всех библиотек.
- Группировка: `[libraries]`, `[versions]`, `[plugins]`.

### Структура build.gradle.kts:
- `build.gradle.kts` (root) — только плагины.
- `composeApp/build.gradle.kts` — KMP-модуль с общим кодом: приватные extension-функции (`configureTargets()`, `configureDependencies()`, `configureAndroidOptions()`).
- `androidApp/build.gradle.kts` — Android-специфичный модуль.

### Типовые зависимости в KMP-проекте:
| Назначение | Библиотека |
|-----------|-----------|
| UI | Compose Multiplatform |
| DI | Kodein-DI |
| Навигация | Voyager / Decompose |
| Асинхронность | Kotlinx Coroutines + Flow |
| Сериализация | Kotlinx Serialization |
| HTTP-клиент | Ktor Client |
| База данных | SQLDelight / Realm |
| Настройки | Multiplatform Settings (russhwolf) |
| Firebase | gitlive firebase (KMP) |
| Дата/время | Kotlinx Datetime |
| Тестирование | Kotlin Test + Turbine (Flow) |

---

## 11. Полный пример новой фичи

### Структура
```
feature_new_feature/
├── compose/
│   ├── NewFeatureScreen.kt
│   ├── components/
│   │   └── NewFeatureItem.kt
├── presentation/
│   ├── di/NewFeatureViewModelModule.kt
│   ├── models/
│   │   ├── NewFeatureState.kt
│   │   └── NewFeatureEvent.kt
│   └── NewFeatureViewModel.kt
├── domain/
│   ├── di/NewFeatureDomainModule.kt
│   ├── models/
│   │   └── NewFeatureData.kt
│   ├── repository/NewFeatureRepository.kt
│   └── utils/NewFeatureError.kt
└── data/
    ├── datasource/
    │   ├── NewFeatureDataSource.kt
    │   └── NewFeatureDataSourceImpl.kt
    ├── di/NewFeatureDataModule.kt
    └── models/
        └── DataNewFeatureData.kt
```

### Event
```kotlin
sealed interface NewFeatureEvent {
    data object ClearAction : NewFeatureEvent
    data object OnRefresh : NewFeatureEvent
    data class OnItemClicked(val id: String) : NewFeatureEvent
}
```

### State
```kotlin
data class NewFeatureState(
    val items: List<NewFeatureData> = emptyList(),
    val isLoading: Boolean = false,
) : Any
```

### Error
```kotlin
enum class NewFeatureError : Error {
    FetchError,
    SaveError,
}
```

### ViewModel
```kotlin
class NewFeatureViewModel(
    private val repository: NewFeatureRepository,
    private val dispatchers: Dispatchers,
) : BaseSharedViewModel<NewFeatureState, NewFeatureAction, NewFeatureEvent>(
    initialState = NewFeatureState()
) {
    init { loadItems() }

    override fun obtainEvent(viewEvent: NewFeatureEvent) {
        when (viewEvent) {
            NewFeatureEvent.ClearAction -> viewAction = null
            NewFeatureEvent.OnRefresh -> loadItems()
            is NewFeatureEvent.OnItemClicked -> handleItemClick(viewEvent.id)
        }
    }

    private fun loadItems() {
        viewState = viewState.copy(isLoading = true)
        withViewModelScope {
            repository.getItems()
                .onSuccess { items ->
                    viewState = viewState.copy(items = items, isLoading = false)
                }
                .onError { error ->
                    viewState = viewState.copy(isLoading = false)
                    viewAction = NewFeatureAction.ShowError(error)
                }
        }
    }

    private fun handleItemClick(id: String) {
        // обработка клика
    }
}
```

### Repository (Domain)
```kotlin
interface NewFeatureRepository {
    suspend fun getItems(): Result<List<NewFeatureData>, Error>
    suspend fun saveItem(item: NewFeatureData): EmptyResult<Error>
}
```

### RepositoryImpl (Data)
```kotlin
class NewFeatureRepositoryImpl(
    private val dataSource: NewFeatureDataSource,
    private val dispatchers: Dispatchers,
) : NewFeatureRepository {
    override suspend fun getItems(): Result<List<NewFeatureData>, Error> =
        withContext(dispatchers.io) {
            dataSource.fetchItems().map { dtos ->
                dtos.map { it.toNewFeatureData() }
            }
        }

    override suspend fun saveItem(item: NewFeatureData): EmptyResult<Error> =
        withContext(dispatchers.io) {
            dataSource.saveItem(item.toDataNewFeatureData())
        }
}
```

### DataSource
```kotlin
interface NewFeatureDataSource {
    suspend fun fetchItems(): Result<List<DataNewFeatureData>, Error>
    suspend fun saveItem(item: DataNewFeatureData): EmptyResult<Error>
}

class NewFeatureDataSourceImpl(
    private val firestore: FirebaseFirestore,
) : NewFeatureDataSource {
    override suspend fun fetchItems(): Result<List<DataNewFeatureData>, Error> {
        return try {
            val snapshot = firestore.collection("items").get()
            val items = snapshot.documents.mapNotNull { it.data<DataNewFeatureData>() }
            Result.Success(items)
        } catch (_: Exception) {
            Result.Error(NewFeatureError.FetchError)
        }
    }
    // ...
}
```

### DI модули
```kotlin
// Data
val newFeatureDataModule = DI.Module("NewFeatureDataModule") {
    bindProvider<NewFeatureDataSource> {
        NewFeatureDataSourceImpl(firestore = instance())
    }
}

// Domain
val newFeatureDomainModule = DI.Module("NewFeatureDomainModule") {
    bindProvider<NewFeatureRepository> {
        NewFeatureRepositoryImpl(
            dataSource = instance(),
            dispatchers = instance(),
        )
    }
}

// ViewModel
val newFeatureViewModelModule = DI.Module("NewFeatureViewModelModule") {
    bindProvider {
        NewFeatureViewModel(
            repository = instance(),
            dispatchers = instance(),
        )
    }
}
```

### Экран
```kotlin
@Composable
fun NewFeatureScreen(modifier: Modifier = Modifier) {
    val viewModel: NewFeatureViewModel by rememberViewModel()
    val state by viewModel.viewStates().collectAsStateWithLifecycle()
    val action by viewModel.viewActions().collectAsStateWithLifecycle()

    LaunchedEffect(action) {
        action?.let {
            when (it) {
                is NewFeatureAction.ShowError -> { /* показать ошибку */ }
            }
            viewModel.obtainEvent(NewFeatureEvent.ClearAction)
        }
    }

    NewFeatureContent(
        state = state,
        onEvent = viewModel::obtainEvent,
    )
}
```

---

## 12. Итоговый чек-лист при создании новой фичи

- [ ] Создан пакет `feature_<name>` с подпакетами `compose`, `presentation`, `domain`, `data`.
- [ ] Определен `sealed interface <Name>Event` (события UI).
- [ ] Определен `data class <Name>State : Any` (состояние экрана).
- [ ] Определен `sealed interface <Name>Action` (одноразовые действия, опционально).
- [ ] Определен `enum <Name>Error : Error` (ошибки фичи).
- [ ] Определен `interface <Name>Repository` в domain.
- [ ] Определен `interface <Name>DataSource` (и impl) в data.
- [ ] Создан `class <Name>RepositoryImpl` в data (с `dispatchers: Dispatchers`).
- [ ] Создан `class <Name>ViewModel : BaseSharedViewModel<...>`.
- [ ] Созданы DI модули для data, domain и presentation.
- [ ] DI модули зарегистрированы в `PlatformSDK` (или корневом DI).
- [ ] Написан `@Composable` экран с подпиской на state/action.
- [ ] Используются `collectAsStateWithLifecycle()` и `LaunchedEffect(action)`.
- [ ] Все io-операции обернуты в `withContext(dispatchers.io)`.
- [ ] Все методы возвращают `Result<T, Error>`.
- [ ] Созданы мапперы DTO <-> Model.
- [ ] Код соответствует стилю: State = data class, Event/Action = sealed interface, имена событий в прошедшем времени.

---

## 13. Стиль кода — сводка

| Элемент | Соглашение |
|---------|-----------|
| **State** | `data class` с default-значениями, implements `Any` |
| **Event** | `sealed interface`, имена в прошедшем времени (`Clicked`, `Loaded`, `Selected`) |
| **Action** | `sealed interface`, имена-существительные (`ShowError`, `NavigateTo`) |
| **ViewModel** | extends `BaseSharedViewModel<State, Action, Event>` |
| **Repository** | interface в domain, impl в data |
| **DataSource** | interface + impl в data |
| **UseCase** | класс с `operator fun invoke()` |
| **Error** | enum implements `Error` interface |
| **DI модуль** | `val moduleName = DI.Module("ModuleName") { }` |
| **Мапперы** | extension-функции: `.toModel()`, `.toDto()` |
| **Пакеты** | snake_case: `feature_my_calendar`, `common_domain_utils` |
| **Layout-папки** | Во feature: `compose/`, `domain/`, `data/` (не `ui/`, не `business/`) |

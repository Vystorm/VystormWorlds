package at.esoren.vystormworlds;

import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;

import Vystorm.vystorm_core.API;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A Core hub for tests without a server. Every manager is the interface's own default: its parts delegate to Core's
 * static code exactly as the plugin's direct calls did before, so static mocks of Core classes keep working.
 */
final class TestCore {
    private TestCore() {}

    static API api() {
        Map<Method, Object> managers = new ConcurrentHashMap<>();
        return mock(API.class, invocation -> {
            Class<?> type = invocation.getMethod().getReturnType();
            if (!type.isInterface()) return RETURNS_DEFAULTS.answer(invocation);
            return managers.computeIfAbsent(invocation.getMethod(), method -> mock(type, CALLS_REAL_METHODS));
        });
    }
}

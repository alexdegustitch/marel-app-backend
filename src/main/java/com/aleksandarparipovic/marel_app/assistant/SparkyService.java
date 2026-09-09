package com.aleksandarparipovic.marel_app.assistant;

import com.aleksandarparipovic.marel_app.assistant.dto.ChatRequest;
import com.aleksandarparipovic.marel_app.auth.CurrentUserService;
import com.aleksandarparipovic.marel_app.auth.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * "Sparky" — a natural-language analytics assistant.
 *
 * <p>It answers free-form Serbian questions by running the model through a
 * function-calling loop: the model calls the read-only tools in
 * {@link SparkyTools} (which wrap the existing analytics engine), the real
 * numbers come back, and the model phrases them. It is told never to invent a
 * figure. Nothing here writes.
 *
 * <p>One quota unit is consumed per user message that reaches the model. The
 * controller has already checked the feature is enabled before this runs.
 */
@Service
@RequiredArgsConstructor
public class SparkyService {

    private static final String SYSTEM_PROMPT = """
            Ti si Sparky, stručan i koristan pomoćnik za fabričku analitiku. \
            Odgovaraj na srpskom, jasno i konkretno. Koristi ISKLJUČIVO brojke koje ti vrate alati \
            — nikada ne izmišljaj i ne procenjuj. Ako ti treba podatak, pozovi odgovarajući alat. \
            Periode iz pitanja pretvori u dateFrom/dateTo (npr. 'jun–avgust 2026' → 2026-06-01 do 2026-08-31; \
            'prvi kvartal 2026' → 2026-01-01 do 2026-03-31). \
            Imena proizvoda, operacija i radnika koja korisnik ukuca su često približna — bez kvačica \
            (npr. 'kuciste' umesto 'Kućište') ili u drugom padežu (npr. 'pumpa' umesto 'pumpe'). \
            ALATI SAMI rešavaju ta približna poklapanja, pa VERUJ rezultatu alata: ako je alat razrešio na \
            malo drugačije ime nego što je korisnik ukucao, koristi ga i to kratko napomeni (npr. „za „Kućište pumpe""). \
            Reci „nema podataka" SAMO kada alat zaista ne vrati nijedan red — nikada zato što ime izgleda drugačije. \
            Ako je ime nejasno ili višeznačno, pozovi `resolve_options` da vidiš kandidate i izaberi najbolji, \
            umesto da odustaneš. Budi sažet ali potpun; kad ima više redova, izdvoji najvažnije.""";

    /** After this many tool rounds, force a plain answer with no more tool calls. */
    private static final int MAX_TOOL_ROUNDS = 4;

    private static final String FALLBACK = "Trenutno ne mogu da sastavim odgovor. Pokušajte ponovo.";

    private final OpenAiClient openAiClient;
    private final SparkyTools tools;
    private final AssistantQuotaService quotaService;
    private final CurrentUserService currentUserService;

    /**
     * Answer one chat turn. Consumes one quota unit up front (this message is
     * about to reach the model), then drives the tool loop.
     */
    @Transactional(readOnly = true)
    public String chat(ChatRequest request) {
        CustomUserDetails user = currentUserService.getCurrentUser();
        quotaService.consumeOrThrow(user.getId(), dailyLimitFor(user));

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(message("system", SYSTEM_PROMPT));
        appendHistory(messages, request.history());
        messages.add(message("user", request.message()));

        List<Object> toolDefs = tools.definitions();

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            Map<String, Object> assistantMsg = openAiClient.chatWithTools(messages, toolDefs);
            List<Map<String, Object>> toolCalls = toolCalls(assistantMsg);

            if (toolCalls.isEmpty()) {
                return contentOrFallback(assistantMsg);
            }

            // Echo the assistant's tool_calls message verbatim, then one tool result per call.
            Map<String, Object> echo = new HashMap<>();
            echo.put("role", "assistant");
            echo.put("content", assistantMsg.get("content")); // may be null alongside tool_calls
            echo.put("tool_calls", toolCalls);
            messages.add(echo);

            for (Map<String, Object> call : toolCalls) {
                messages.add(toolResult(call));
            }
        }

        // Rounds exhausted — force a final answer with no tools available.
        Map<String, Object> finalMsg = openAiClient.chatWithTools(messages, null);
        return contentOrFallback(finalMsg);
    }

    private void appendHistory(List<Map<String, Object>> messages, List<ChatRequest.Message> history) {
        if (history == null) {
            return;
        }
        for (ChatRequest.Message m : history) {
            if (m == null || m.content() == null || m.content().isBlank()) {
                continue;
            }
            String role = m.role();
            if ("user".equals(role) || "assistant".equals(role)) {
                messages.add(message(role, m.content()));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> toolCalls(Map<String, Object> assistantMsg) {
        Object raw = assistantMsg.get("tool_calls");
        if (raw instanceof List<?> list && !list.isEmpty()) {
            return (List<Map<String, Object>>) raw;
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toolResult(Map<String, Object> call) {
        String id = (String) call.get("id");
        String name = null;
        String arguments = null;
        if (call.get("function") instanceof Map<?, ?> function) {
            Object n = function.get("name");
            Object args = function.get("arguments");
            name = n == null ? null : n.toString();
            arguments = args == null ? null : args.toString();
        }
        String result = tools.execute(name, arguments);

        Map<String, Object> msg = new HashMap<>();
        msg.put("role", "tool");
        msg.put("tool_call_id", id);
        msg.put("content", result);
        return msg;
    }

    private String contentOrFallback(Map<String, Object> assistantMsg) {
        Object content = assistantMsg.get("content");
        if (content instanceof String s && !s.isBlank()) {
            return s.strip();
        }
        return FALLBACK;
    }

    private Map<String, Object> message(String role, String content) {
        Map<String, Object> m = new HashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    /** admin / supervisor get the larger daily budget; everyone else the default. */
    private int dailyLimitFor(CustomUserDetails user) {
        boolean privileged = user.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equalsIgnoreCase("ROLE_admin") || a.equalsIgnoreCase("ROLE_supervisor"));
        return privileged
                ? AssistantQuotaService.PRIVILEGED_DAILY_LIMIT
                : AssistantQuotaService.DEFAULT_DAILY_LIMIT;
    }
}

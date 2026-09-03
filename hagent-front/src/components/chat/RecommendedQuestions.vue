<template>
  <div v-if="questions?.length" class="recommended-questions" role="group" aria-label="推荐追问">
    <button
      v-for="(q, idx) in questions.slice(0, 3)"
      :key="`${idx}-${q}`"
      class="hagent-badge--pill"
      type="button"
      @click="emit('select', q)"
    >
      {{ q }}
    </button>
  </div>
</template>

<script setup lang="ts">
withDefaults(
  defineProps<{
    questions: string[]
  }>(),
  { questions: () => [] }
)

const emit = defineEmits<{
  (e: 'select', text: string): void
}>()
</script>

<style scoped>
.recommended-questions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
  margin-top: var(--space-3);
}

.hagent-badge--pill {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 30px;
  padding: 6px 14px;
  border-radius: var(--radius-full);
  border: 1px solid var(--accent-border);
  background: var(--accent-soft);
  color: var(--accent);
  font-size: var(--text-sm);
  line-height: 1.4;
  font-weight: 500;
  cursor: pointer;
  user-select: none;
  white-space: nowrap;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  transition:
    background var(--dur-fast) var(--ease),
    color var(--dur-fast) var(--ease),
    border-color var(--dur-fast) var(--ease),
    transform var(--dur-fast) var(--ease),
    box-shadow var(--dur-fast) var(--ease);
}

.hagent-badge--pill:hover {
  background: var(--accent);
  color: #fff;
  border-color: var(--accent);
  transform: translateY(-1px);
  box-shadow: 0 2px 10px rgba(0, 0, 0, 0.08);
}

.hagent-badge--pill:active {
  transform: translateY(0);
  box-shadow: none;
}

.hagent-badge--pill:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 2px;
}

[data-theme='dark'] .hagent-badge--pill:hover {
  color: #0f172a;
}
</style>

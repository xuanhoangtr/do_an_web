(() => {
  const marker = document.getElementById('admin-payment-status-poll');
  if (!marker) return;

  const intervalMs = 5000;
  const maxWaitMs = 120000;
  const initial = {
    orderStatus: marker.dataset.orderStatus,
    paymentStatus: marker.dataset.paymentStatus,
    fulfillmentStatus: marker.dataset.fulfillmentStatus
  };
  const startedAt = Date.now();
  let timer = null;
  let controller = null;
  let inFlight = false;
  let stopped = false;

  const stop = () => {
    stopped = true;
    if (timer !== null) window.clearTimeout(timer);
    if (controller) controller.abort();
  };

  const schedule = () => {
    if (stopped || inFlight) return;
    if (Date.now() - startedAt >= maxWaitMs) {
      stop();
      return;
    }
    timer = window.setTimeout(check, intervalMs);
  };

  async function check() {
    if (stopped || inFlight) return;
    inFlight = true;
    controller = new AbortController();
    try {
      const response = await fetch(marker.dataset.url, {
        method: 'GET',
        credentials: 'same-origin',
        cache: 'no-store',
        headers: { Accept: 'application/json' },
        signal: controller.signal
      });
      if (!response.ok) throw new Error('Payment status unavailable');
      const state = await response.json();
      if (state.orderStatus !== initial.orderStatus
          || state.paymentStatus !== initial.paymentStatus
          || state.fulfillmentStatus !== initial.fulfillmentStatus) {
        stop();
        window.location.reload();
        return;
      }
    } catch (error) {
      // Keep trying until the bounded polling window ends; the endpoint is read-only.
    } finally {
      inFlight = false;
      controller = null;
      schedule();
    }
  }

  window.addEventListener('pagehide', stop, { once: true });
  timer = window.setTimeout(check, intervalMs);
})();

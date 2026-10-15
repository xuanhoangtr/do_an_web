(() => {
  const marker = document.getElementById('payment-status-poll');
  if (!marker) return;

  const intervalMs = 3000;
  const maxWaitMs = 120000;
  const message = document.getElementById('payment-live-message');
  const orderStatus = document.getElementById('payment-order-status');
  const retryButton = document.getElementById('payment-check-again');
  let startedAt = Date.now();
  let timer = null;
  let controller = null;
  let inFlight = false;
  let stopped = false;

  const stop = () => {
    stopped = true;
    if (timer !== null) window.clearTimeout(timer);
    if (controller) controller.abort();
  };

  const showRetry = () => {
    if (message) message.textContent = 'Chưa có kết quả xác nhận cuối cùng. Đơn vẫn đang chờ; bạn có thể kiểm tra lại hoặc mở chi tiết đơn.';
    if (retryButton) retryButton.classList.remove('d-none');
  };

  const schedule = () => {
    if (stopped || inFlight) return;
    if (Date.now() - startedAt >= maxWaitMs) {
      stop();
      showRetry();
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
      if (state.paid && state.redirectUrl) {
        stop();
        window.location.assign(state.redirectUrl);
        return;
      }
      if (orderStatus && state.orderStatus) orderStatus.textContent = state.orderStatus;
      if (message && state.message) message.textContent = state.message;
      if (state.terminal) {
        stop();
        return;
      }
    } catch (error) {
      if (error.name !== 'AbortError' && message) {
        message.textContent = 'Đang chờ xác nhận thanh toán. Hệ thống sẽ tự kiểm tra lại.';
      }
    } finally {
      inFlight = false;
      controller = null;
      schedule();
    }
  }

  if (retryButton) {
    retryButton.addEventListener('click', () => {
      if (stopped) {
        stopped = false;
        startedAt = Date.now();
        retryButton.classList.add('d-none');
      }
      if (timer !== null) window.clearTimeout(timer);
      check();
    });
  }

  window.addEventListener('pagehide', stop, { once: true });
  timer = window.setTimeout(check, intervalMs);
})();

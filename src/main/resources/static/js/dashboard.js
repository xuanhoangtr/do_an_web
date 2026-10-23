/* Dashboard chart reads server-rendered data attributes; no inline JavaScript is needed. */
(function () {
    const form = document.getElementById("dashboardRangeForm");
    const preset = document.getElementById("rangePreset");
    const from = document.getElementById("rangeFrom");
    const to = document.getElementById("rangeTo");
    if (form && preset && from && to) {
        form.addEventListener("submit", event => {
            from.setCustomValidity("");
            to.setCustomValidity("");
            if (preset.value === "custom" && from.value && to.value && from.value > to.value) {
                to.setCustomValidity("Ngày kết thúc phải bằng hoặc sau ngày bắt đầu.");
                to.reportValidity();
                event.preventDefault();
            }
        });
    }

    const source = document.getElementById("revenueChartData");
    const canvas = document.getElementById("revenueChart");
    if (!source || !canvas || !window.Chart) return;
    const labels = (source.dataset.labels || "").split("|").filter(Boolean);
    const values = (source.dataset.values || "").split("|").filter(Boolean).map(Number);
    new window.Chart(canvas, {
        type: "line",
        data: { labels, datasets: [{
            label: "Doanh thu (đ)", data: values, borderColor: "#c95642",
            backgroundColor: "rgba(201,86,66,.12)", fill: true, tension: .35, pointRadius: 3
        }]},
        options: {
            responsive: true, maintainAspectRatio: false,
            interaction: { mode: "index", intersect: false },
            plugins: {
                legend: { display: false },
                tooltip: {
                    callbacks: {
                        label: context => `Doanh thu: ${new Intl.NumberFormat("vi-VN").format(context.parsed.y)} đ`
                    }
                }
            },
            scales: {
                y: {
                    beginAtZero: true,
                    title: { display: true, text: "Doanh thu (đ)" },
                    ticks: { callback: value => new Intl.NumberFormat("vi-VN", {
                        notation: "compact", maximumFractionDigits: 1
                    }).format(value) }
                },
                x: {
                    grid: { display: false },
                    ticks: { autoSkip: true, maxTicksLimit: 12, maxRotation: 0 }
                }
            }
        }
    });
})();

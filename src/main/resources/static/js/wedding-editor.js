(function () {
    const steps = Object.freeze(["couple", "event", "media", "guests"]);

    function normalizeStep(step, fallback = "couple") {
        return steps.includes(step) ? step : fallback;
    }

    function initializeEditorWizard({tabButtons, previousButton, nextButton, stepField, tabApi}) {
        const buttonFor = (step) => tabButtons.find((button) => button.dataset.editorStep === step);
        const activeStep = () => {
            const activeButton = tabButtons.find((button) => button.classList.contains("active"));
            return activeButton ? normalizeStep(activeButton.dataset.editorStep) : "couple";
        };
        const syncControls = (step) => {
            const currentIndex = steps.indexOf(normalizeStep(step));
            stepField.value = steps[currentIndex];
            previousButton.hidden = currentIndex === 0;
            nextButton.hidden = currentIndex === steps.length - 1;
        };
        const showStep = (step) => {
            const normalizedStep = normalizeStep(step);
            const targetButton = buttonFor(normalizedStep);
            if (targetButton) tabApi.getOrCreateInstance(targetButton).show();
        };

        tabButtons.forEach((button) => {
            button.addEventListener("shown.bs.tab", (event) => {
                syncControls(event.target.dataset.editorStep);
            });
        });
        previousButton.addEventListener("click", () => {
            const currentIndex = steps.indexOf(activeStep());
            if (currentIndex > 0) showStep(steps[currentIndex - 1]);
        });
        nextButton.addEventListener("click", () => {
            const currentIndex = steps.indexOf(activeStep());
            if (currentIndex < steps.length - 1) showStep(steps[currentIndex + 1]);
        });

        syncControls(activeStep());
        return {showStep, syncControls};
    }

    function boot() {
        const form = document.getElementById("weddingEditor");
        if (!form || !window.bootstrap || !window.bootstrap.Tab) return;

        const tabButtons = Array.from(form.querySelectorAll("[data-editor-step]"));
        const previousButton = document.getElementById("editorPrevious");
        const nextButton = document.getElementById("editorNext");
        const stepField = document.getElementById("editorStep");
        if (!tabButtons.length || !previousButton || !nextButton || !stepField) return;

        const wizard = initializeEditorWizard({
            tabButtons,
            previousButton,
            nextButton,
            stepField,
            tabApi: window.bootstrap.Tab
        });
        const requestedStep = new URLSearchParams(window.location.search).get("step");
        const initialStep = normalizeStep(requestedStep, normalizeStep(form.dataset.activeStep));
        if (initialStep !== activeEditorStep(tabButtons)) wizard.showStep(initialStep);
    }

    function activeEditorStep(tabButtons) {
        const activeButton = tabButtons.find((button) => button.classList.contains("active"));
        return activeButton ? normalizeStep(activeButton.dataset.editorStep) : "couple";
    }

    window.WeddingEditorWizard = {steps, normalizeStep, initializeEditorWizard};
    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", boot, {once: true});
    } else {
        boot();
    }
})();

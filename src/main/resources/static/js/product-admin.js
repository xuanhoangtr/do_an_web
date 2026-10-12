(() => {
    const nameInput = document.querySelector("[data-product-name]");
    const skuInput = document.querySelector("[data-product-sku]");
    if (nameInput && skuInput) {
        const skuAutoInput = document.querySelector("[data-product-sku-auto]");
        let manualSku = !skuAutoInput || skuAutoInput.value !== "true";
        skuInput.addEventListener("input", () => {
            manualSku = true;
            if (skuAutoInput) skuAutoInput.value = "false";
        });
        nameInput.addEventListener("input", () => {
            if (manualSku) return;
            const base = nameInput.value.trim()
                .replace(/[đĐ]/g, (letter) => letter === "đ" ? "d" : "D")
                .normalize("NFD")
                .replace(/[\u0300-\u036f]/g, "")
                .toLowerCase()
                .replace(/[^a-z0-9]+/g, "-")
                .replace(/^-+|-+$/g, "")
                .slice(0, 80)
                .replace(/-+$/g, "");
            skuInput.value = base;
        });
    }

    const categorySearch = document.querySelector("[data-category-search]");
    const categorySelect = document.querySelector("[data-category-select]");
    if (categorySearch && categorySelect) {
        categorySearch.addEventListener("input", () => {
            const query = categorySearch.value.trim().toLocaleLowerCase("vi");
            for (const option of categorySelect.options) {
                option.hidden = option.value !== "" && !option.textContent.toLocaleLowerCase("vi").includes(query);
            }
        });
    }

    const fileInput = document.querySelector("[data-product-image]");
    const preview = document.querySelector("[data-product-image-preview]");
    const urlInput = document.querySelector("[data-product-image-url]");
    const mediaIdInput = document.querySelector("[data-managed-media-id]");
    const removeButton = document.querySelector("[data-remove-product-image]");
    let previewUrl = null;
    const showPreview = (url) => {
        if (!preview) return;
        preview.src = url;
        preview.classList.remove("d-none");
    };
    const clearImage = () => {
        if (previewUrl) URL.revokeObjectURL(previewUrl);
        previewUrl = null;
        if (fileInput) fileInput.value = "";
        if (urlInput) urlInput.value = "";
        if (mediaIdInput) mediaIdInput.value = "";
        preview?.removeAttribute("src");
        preview?.classList.add("d-none");
    };
    fileInput?.addEventListener("change", () => {
        const file = fileInput.files?.[0];
        if (!file) return;
        if (previewUrl) URL.revokeObjectURL(previewUrl);
        previewUrl = URL.createObjectURL(file);
        showPreview(previewUrl);
    });
    urlInput?.addEventListener("input", () => {
        if (mediaIdInput) mediaIdInput.value = "";
        if (fileInput) fileInput.value = "";
        if (previewUrl) URL.revokeObjectURL(previewUrl);
        previewUrl = null;
        if (urlInput.value.trim()) showPreview(urlInput.value.trim());
        else preview?.classList.add("d-none");
    });
    removeButton?.addEventListener("click", clearImage);
    window.addEventListener("pagehide", () => { if (previewUrl) URL.revokeObjectURL(previewUrl); }, { once: true });
})();

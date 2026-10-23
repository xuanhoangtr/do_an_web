document.querySelectorAll('[data-proof-preview]').forEach((input) => {
    const preview = document.getElementById(input.dataset.proofPreview);
    const modal = input.closest('.modal');
    let previewUrl = null;

    const clearPreview = () => {
        if (previewUrl) URL.revokeObjectURL(previewUrl);
        previewUrl = null;
        preview.removeAttribute('src');
        preview.classList.add('d-none');
    };

    input.addEventListener('change', () => {
        clearPreview();
        const file = input.files && input.files[0];
        if (!file || !['image/jpeg', 'image/png', 'image/webp'].includes(file.type)) return;
        previewUrl = URL.createObjectURL(file);
        preview.src = previewUrl;
        preview.classList.remove('d-none');
    });

    modal.addEventListener('hidden.bs.modal', () => {
        input.value = '';
        clearPreview();
    });
});

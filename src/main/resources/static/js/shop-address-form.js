(() => {
  const provinceSelects = document.querySelectorAll('[data-address-province]');
  provinceSelects.forEach((provinceSelect) => {
    const form = provinceSelect.closest('[data-shop-address-form]') || provinceSelect.closest('form[data-route-form]');
    const wardSelect = form?.querySelector('[data-address-ward]');
    if (!wardSelect) return;

    const loadWards = async (provinceCode, selectedWard = '') => {
      wardSelect.replaceChildren(new Option('Đang tải phường / xã…', ''));
      wardSelect.disabled = true;
      if (!provinceCode) {
        wardSelect.replaceChildren(new Option('Chọn phường / xã', ''));
        return;
      }
      try {
        const response = await fetch(`/api/v1/provinces/${encodeURIComponent(provinceCode)}/wards`, {
          headers: { Accept: 'application/json' }, credentials: 'same-origin', cache: 'no-store'
        });
        if (!response.ok) throw new Error('ward list unavailable');
        const wards = await response.json();
        wardSelect.replaceChildren(new Option('Chọn phường / xã', ''));
        wards.forEach((ward) => {
          const option = new Option(ward.name, ward.code);
          option.selected = ward.code === selectedWard;
          wardSelect.add(option);
        });
        wardSelect.disabled = false;
      } catch (_) {
        wardSelect.replaceChildren(new Option('Không tải được danh mục; vui lòng chọn lại tỉnh', ''));
      }
    };

    provinceSelect.addEventListener('change', () => loadWards(provinceSelect.value));
    const initialProvince = provinceSelect.dataset.selectedProvince;
    const initialWard = provinceSelect.dataset.selectedWard;
    if (initialProvince) loadWards(initialProvince, initialWard);
    else wardSelect.disabled = true;
  });
})();

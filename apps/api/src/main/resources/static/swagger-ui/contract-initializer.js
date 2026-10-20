window.onload = function () {
  window.ui = SwaggerUIBundle({
    url: '/contracts/openapi.yaml',
    dom_id: '#swagger-ui',
    presets: [SwaggerUIBundle.presets.apis],
    validatorUrl: null,
    supportedSubmitMethods: [],
    persistAuthorization: false
  });
};

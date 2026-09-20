import eslint from "@eslint/js";
import globals from "globals";

export default [
    {
        ignores: ["target/**", "node_modules/**"]
    },
    eslint.configs.recommended,
    {
        files: ["src/main/resources/static/js/**/*.js"],
        languageOptions: {
            ecmaVersion: "latest",
            sourceType: "script",
            globals: {
                ...globals.browser,
                ...globals.es2021
            }
        },
        rules: {
            "no-console": "off",
            "no-unused-vars": ["error", {
                "args": "none",
                "caughtErrors": "none",
                "varsIgnorePattern": "^(postForm|connectLiveUpdates|connectStompUpdates)$"
            }]
        }
    }
];

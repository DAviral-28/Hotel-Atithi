/** @type {import('tailwindcss').Config} */
export default {
  content: [
    "./index.html",
    "./src/**/*.{js,ts,jsx,tsx}",
  ],
  darkMode: 'class',
  theme: {
    extend: {
      colors: {
        hotel: {
          50: '#fbf8f3',
          100: '#f5efe4',
          200: '#eadbc6',
          300: '#dcbe9f',
          400: '#cb9f77',
          500: '#b88156',
          600: '#a36b46',
          700: '#84533a',
          800: '#6c4433',
          900: '#58392d',
          gold: '#d4af37',
          emerald: '#0f766e',
        }
      },
      fontFamily: {
        sans: ['Outfit', 'Inter', 'sans-serif'],
      }
    },
  },
  plugins: [],
}

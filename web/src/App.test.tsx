import { screen } from '@testing-library/react'
import { expect, it } from 'vitest'
import { App } from './App'
import { renderWithProviders } from './test/render'

it('renders the brand mark', () => {
  renderWithProviders(<App />)

  expect(screen.getByRole('heading', { name: '동행' })).toBeVisible()
})

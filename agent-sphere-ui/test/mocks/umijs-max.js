/** Test stub for `@umijs/max`: only the members used by pure model modules. */

const messages = {};

function formatMessage({ id, defaultMessage }) {
  return messages[id] || defaultMessage || id;
}

const intl = { formatMessage };

module.exports = {
  useIntl: () => intl,
  getIntl: () => intl,
  getLocale: () => 'zh-CN',
  setLocale: () => {},
  history: { push: () => {}, replace: () => {}, goBack: () => {} },
  request: async () => ({}),
  useModel: () => ({ initialState: {} }),
  useParams: () => ({}),
  useLocation: () => ({ pathname: '/', search: '', hash: '' }),
  useNavigate: () => () => {},
  Link: ({ children }) => children,
};

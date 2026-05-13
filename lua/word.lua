local M = {
	style = vim.fn.Style,
	suggest = vim.fn.Suggest,
}

M.setup = function(opts)
	M.config = opts
end

return M
